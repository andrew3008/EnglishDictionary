package space.br1440.platform.tracing.bench;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.otel.extension.propagation.PlatformTraceControlPropagator;

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
 *   <li><b>platformExtract</b> — {@code PlatformTraceControlPropagator}: парсинг
 *       {@code X-Trace-On}/{@code X-QA-Trace}/{@code X-Request-Id} → {@code PlatformTraceControl}
 *       в Context (вход сэмплера, см. {@code ForceHeaderRule});</li>
 *   <li><b>platformExtractNoHeaders</b> — тот же extract без платформенных заголовков
 *       (типичный трафик: подавляющее большинство запросов без X-Trace-On);</li>
 *   <li><b>platformInject</b> — inject платформенных заголовков; ВАЖНО: проходит через
 *       {@code PlatformPropagationGate} (secure-by-default DENY, Фаза 12) — измеряется
 *       именно production-цена, включая gate-проверку.</li>
 * </ul>
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
    private PlatformTraceControlPropagator platform;
    private Context contextWithSpan;
    private Map<String, String> w3cCarrier;
    private Map<String, String> platformCarrier;
    private Map<String, String> emptyCarrier;

    @Setup(Level.Trial)
    public void setUp() {
        w3c = W3CTraceContextPropagator.getInstance();
        platform = new PlatformTraceControlPropagator(
                PlatformHeaders.X_TRACE_ON, PlatformHeaders.X_QA_TRACE, PlatformHeaders.X_REQUEST_ID);

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

    /** Входящий запрос с X-Trace-On: парсинг → PlatformTraceControl в Context. */
    @Benchmark
    public void platformExtract(Blackhole bh) {
        bh.consume(platform.extract(Context.root(), platformCarrier, GETTER));
    }

    /** Типичный трафик: платформенных заголовков нет. */
    @Benchmark
    public void platformExtractNoHeaders(Blackhole bh) {
        bh.consume(platform.extract(Context.root(), emptyCarrier, GETTER));
    }

    /** Исходящий запрос: цена inject включая PlatformPropagationGate (DENY-default). */
    @Benchmark
    public void platformInject(Blackhole bh) {
        Map<String, String> carrier = new HashMap<>(4);
        platform.inject(contextWithSpan, carrier, SETTER);
        bh.consume(carrier);
    }
}
