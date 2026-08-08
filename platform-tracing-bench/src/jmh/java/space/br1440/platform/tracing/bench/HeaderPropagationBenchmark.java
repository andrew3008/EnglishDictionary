package space.br1440.platform.tracing.bench;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Стоимость inject/extract пропагации (Фаза 17, PR-1; REQ-SAMPLING-001, §3 требований).
 * <p>
 * Extract выполняется на КАЖДОМ входящем запросе (server-side hot path), inject — на каждом
 * исходящем. Измеряются оба слоя пропагации платформы:
 * <ul>
 *   <li><b>w3cInject / w3cExtract</b> — стандартный W3C tracecontext (baseline OTel);</li>
 *   <li><b>platformExtract</b> — named {@code platform-trace-control} propagator: парсинг
 *       {@code X-Trace-On}/{@code X-QA-Trace}/{@code X-Request-Id} → {@code InboundTraceControl}
 *       в Context (вход сэмплера, см. {@code ForceHeaderRule});</li>
 *   <li><b>platformExtractNoHeaders</b> — тот же extract без платформенных заголовков
 *       (типичный трафик: подавляющее большинство запросов без X-Trace-On);</li>
 *   <li><b>platformInject</b> — inject платформенных заголовков без {@code OutboundPropagationDecision}
 *       в Context: измеряется secure-by-default fast path (без decision ни один заголовок не
 *       формируется — это НЕ поведение {@code PlatformPropagationGate}, а отдельный контракт
 *       {@code DefaultTraceControlHeaderInjector}; см. §0.6 плана propagation cleanup).</li>
 * </ul>
 * <p>
 * <b>Supported-boundary harness (propagation cleanup, Slice 1):</b> {@code platform} — это итоговый
 * safe-wrapped {@link TextMapPropagator}, полученный через реальный {@link AutoConfiguredOpenTelemetrySdk}
 * и named SPI ({@code otel.propagators=platform-trace-control}), а не прямое конструирование
 * package-private implementation-типа. Benchmark-модуль не имеет compile-time dependency на
 * {@code TraceControlPropagator} (см. G-PROP-6).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class HeaderPropagationBenchmark {

    private static final TextMapSetter<Map<String, String>> SETTER =
            (carrier, key, value) -> carrier.put(key, value);
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }
        @Override public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
        }
    };

    private W3CTraceContextPropagator w3c;
    private AutoConfiguredOpenTelemetrySdk sdk;
    private TextMapPropagator platform;
    private Context contextWithSpan;
    private Map<String, String> w3cCarrier;
    private Map<String, String> platformCarrier;
    private Map<String, String> emptyCarrier;

    @Setup(Level.Trial)
    public void setUp() {
        w3c = W3CTraceContextPropagator.getInstance();

        // Startup/ServiceLoader/configuration сборка SDK не входит в measured operation:
        // final propagator извлекается один раз здесь, benchmark-методы измеряют только inject/extract.
        sdk = AutoConfiguredOpenTelemetrySdk.builder()
                .disableShutdownHook()
                .addPropertiesSupplier(() -> Map.of(
                        "otel.propagators", "platform-trace-control",
                        "otel.traces.exporter", "none",
                        "otel.metrics.exporter", "none",
                        "otel.logs.exporter", "none"))
                .build();
        platform = sdk.getOpenTelemetrySdk().getPropagators().getTextMapPropagator();

        contextWithSpan = Context.root().with(Span.wrap(SpanContext.create(
                "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331",
                TraceFlags.getSampled(), TraceState.getDefault())));

        w3cCarrier = new HashMap<>();
        w3cCarrier.put("traceparent",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");

        platformCarrier = new HashMap<>();
        platformCarrier.put(PlatformHeaders.X_TRACE_ON, "on");
        platformCarrier.put(PlatformHeaders.X_REQUEST_ID, "req-7782-bench");

        emptyCarrier = new HashMap<>();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        sdk.getOpenTelemetrySdk().close();
    }

    @Benchmark
    public void w3cInject(Blackhole bh) {
        Map<String, String> carrier = new HashMap<>(4);
        w3c.inject(contextWithSpan, carrier, SETTER);
        bh.consume(carrier);
    }

    @Benchmark
    public void w3cExtract(Blackhole bh) {
        bh.consume(w3c.extract(Context.root(), w3cCarrier, GETTER));
    }

    /** Входящий запрос с X-Trace-On: парсинг → InboundTraceControl в Context. */
    @Benchmark
    public void platformExtract(Blackhole bh) {
        bh.consume(platform.extract(Context.root(), platformCarrier, GETTER));
    }

    /** Типичный трафик: платформенных заголовков нет. */
    @Benchmark
    public void platformExtractNoHeaders(Blackhole bh) {
        bh.consume(platform.extract(Context.root(), emptyCarrier, GETTER));
    }

    /**
     * Исходящий запрос без {@code OutboundPropagationDecision} в Context: измеряется
     * secure-by-default fast path {@code DefaultTraceControlHeaderInjector} (ни один заголовок
     * не формируется без явного decision) — не gate-проверка.
     */
    @Benchmark
    public void platformInject(Blackhole bh) {
        Map<String, String> carrier = new HashMap<>(4);
        platform.inject(contextWithSpan, carrier, SETTER);
        bh.consume(carrier);
    }
}
