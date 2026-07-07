package space.br1440.platform.tracing.bench;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.common.CompletableResultCode;
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
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.spi.SensitiveDataRule;
import space.br1440.platform.tracing.core.DefaultPlatformTracing;
import space.br1440.platform.tracing.otel.extension.processor.ClassificationSpanProcessor;
import space.br1440.platform.tracing.otel.extension.processor.EnrichingSpanProcessor;
import space.br1440.platform.tracing.otel.extension.processor.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.extension.processor.ScrubbingSpanProcessor;
import space.br1440.platform.tracing.otel.extension.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.extension.scrubbing.BuiltInSensitiveDataRules;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Бенчмарк cost'а span-pipeline'а через {@link PlatformCompositeSpanProcessor}.
 * <p>
 * Три измерения для baseline'а риска <b>H1</b> (overhead {@code ReadWriteSpan.toSpanData()} на
 * горячем пути {@code onEnding}; см. architecture review). Каждый реальный процессор
 * ({@code Enriching}/{@code Scrubbing}/{@code Validating}/{@code Classification}) независимо
 * вызывает {@code span.toSpanData()}, материализуя полный immutable-снимок span'а ради чтения
 * status/attributes/resource — это основной источник аллокаций композита.
 *
 * <ul>
 *   <li>{@link #compositeObvyazka(Blackhole)} — 4 noop-делегата, пустой span: чистая стоимость
 *       обвязки композита (обход списка, fan-out onStart/onEnding), без полезной работы и без
 *       {@code toSpanData()}.</li>
 *   <li>{@link #prodLikeSpanNoopPipeline(Blackhole)} — 4 noop-делегата, но span населён prod-like
 *       атрибутами: изолирует стоимость самой установки атрибутов + фасада, чтобы вычесть её из
 *       реального прогона.</li>
 *   <li>{@link #prodLikeSpanRealPipeline(Blackhole)} — реальные 4 процессора в production-порядке
 *       (как в {@code PlatformSpanProcessorFactory}) на том же prod-like span'е: это и есть
 *       <b>baseline H1</b>. Дельта с {@link #prodLikeSpanNoopPipeline(Blackhole)} = стоимость
 *       реальных {@code onEnding} включая аллокации {@code toSpanData()}.</li>
 *   <li>{@link #prodLikeSpanRealPipelineNoScrubbing(Blackhole)} — тот же реальный композит без
 *       {@code ScrubbingSpanProcessor} (Enriching+Validating+Classification — все вызывают
 *       {@code toSpanData()}, но почти без собственной работы). Изолирует H1-поверхность
 *       ({@code toSpanData}) от regex-движка scrubbing'а: дельта с noop ≈ налог
 *       {@code toSpanData()}, а дельта full-real минус этот замер ≈ собственная цена scrubbing'а.</li>
 * </ul>
 * <p>
 * Ключевая метрика H1 — не только {@code avgt} (ns/op), но и {@code gc.alloc.rate.norm} (байт/op)
 * из GC-профайлера: именно она показывает аллокационный налог {@code toSpanData()} на span.
 * <p>
 * <b>ProcessorContext refactor сознательно НЕ делается</b> (anti-over-engineering): этот класс
 * только измеряет baseline. Решение о шаринге единого {@code SpanData}-снимка между делегатами
 * принимается отдельно и лишь если baseline покажет значимый налог на prod-like нагрузке.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class CompositePipelineBenchmark {

    private OpenTelemetrySdk noopSdk;
    private OpenTelemetrySdk realSdk;
    private OpenTelemetrySdk realNoScrubbingSdk;
    private PlatformTracing noopPipeline;
    private PlatformTracing realPipeline;
    private PlatformTracing realNoScrubbingPipeline;

    @Setup(Level.Trial)
    public void setUp() {
        // 4 noop-делегата: каждый возвращает true для isStartRequired/isEndRequired, чтобы
        // композит реально вызывал их (иначе fast-path отключает обход списка).
        List<SpanProcessor> noopDelegates = List.of(
                new NoopSpanProcessor(),
                new NoopSpanProcessor(),
                new NoopSpanProcessor(),
                new NoopSpanProcessor()
        );
        noopSdk = buildSdk(new PlatformCompositeSpanProcessor(noopDelegates));
        noopPipeline = new DefaultPlatformTracing(noopSdk);

        // Реальный композит в production-порядке (см. PlatformSpanProcessorFactory):
        // Enriching -> Scrubbing -> Validating -> Classification. Watchdog/Metrics не включены:
        // они не читают toSpanData() на per-span onEnding (watchdog — таймерный, metrics — onEnd).
        List<SensitiveDataRule> rules = new ArrayList<>();
        for (String name : BuiltInSensitiveDataRules.defaultConfigNames()) {
            SensitiveDataRule rule = BuiltInSensitiveDataRules.resolve(name);
            if (rule != null) {
                rules.add(rule);
            }
        }
        List<SpanProcessor> realDelegates = List.of(
                new EnrichingSpanProcessor(),
                // Без HMAC-ключа: HASH деградирует до MASK — на аллокации toSpanData() не влияет.
                new ScrubbingSpanProcessor(rules),
                new ValidatingSpanProcessor(false),
                new ClassificationSpanProcessor(Duration.ofSeconds(5), Duration.ofSeconds(1))
        );
        realSdk = buildSdk(new PlatformCompositeSpanProcessor(realDelegates));
        realPipeline = new DefaultPlatformTracing(realSdk);

        // Тот же реальный композит, но БЕЗ scrubbing'а. Enriching/Validating/Classification — это
        // как раз процессоры, вызывающие toSpanData() (status/resource), но с минимальной собственной
        // работой. Изолирует H1-поверхность (аллокации toSpanData) от regex-движка scrubbing'а.
        List<SpanProcessor> realNoScrubbingDelegates = List.of(
                new EnrichingSpanProcessor(),
                new ValidatingSpanProcessor(false),
                new ClassificationSpanProcessor(Duration.ofSeconds(5), Duration.ofSeconds(1))
        );
        realNoScrubbingSdk = buildSdk(new PlatformCompositeSpanProcessor(realNoScrubbingDelegates));
        realNoScrubbingPipeline = new DefaultPlatformTracing(realNoScrubbingSdk);
    }

    private static OpenTelemetrySdk buildSdk(SpanProcessor composite) {
        return OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(composite)
                        .build())
                .build();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        noopSdk.close();
        realSdk.close();
        realNoScrubbingSdk.close();
    }

    @Benchmark
    public void compositeObvyazka(Blackhole bh) {
        try (SpanHandle scope = noopPipeline.manual().operation("bench-pipeline").start()) {
            bh.consume(scope);
        }
    }

    @Benchmark
    public void prodLikeSpanNoopPipeline(Blackhole bh) {
        try (SpanHandle scope = noopPipeline.manual().operation("bench-pipeline").start()) {
            populateProdLikeAttributes();
            bh.consume(scope);
        }
    }

    @Benchmark
    public void prodLikeSpanRealPipeline(Blackhole bh) {
        try (SpanHandle scope = realPipeline.manual().operation("bench-pipeline").start()) {
            populateProdLikeAttributes();
            bh.consume(scope);
        }
    }

    @Benchmark
    public void prodLikeSpanRealPipelineNoScrubbing(Blackhole bh) {
        try (SpanHandle scope = realNoScrubbingPipeline.manual().operation("bench-pipeline").start()) {
            populateProdLikeAttributes();
            bh.consume(scope);
        }
    }

    /**
     * Населяет span репрезентативным набором атрибутов HTTP-server запроса (~9 ключей), включая
     * пару чувствительных, чтобы {@code ScrubbingSpanProcessor} выполнял реальную работу, а
     * {@code toSpanData().getAttributes()} материализовал нетривиальный снимок.
     */
    private static void populateProdLikeAttributes() {
        Span span = Span.current();
        span.setAttribute("http.request.method", "POST");
        span.setAttribute("url.path", "/api/v1/orders/12345");
        span.setAttribute("http.route", "/api/v1/orders/{id}");
        span.setAttribute("http.response.status_code", 200L);
        span.setAttribute("server.address", "orders.svc.cluster.local");
        span.setAttribute("user_agent.original", "Mozilla/5.0 (platform-bench) AppleWebKit/537.36");
        span.setAttribute("enduser.id", "user-7782");
        span.setAttribute("http.request.header.authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig");
        span.setAttribute("db.password", "s3cr3t-value");
    }

    /**
     * Минимальный noop {@link SpanProcessor}: hot-path методы возвращают сразу, чтобы изолировать
     * стоимость самого композита, а не делегата.
     */
    private static final class NoopSpanProcessor implements SpanProcessor {
        @Override public void onStart(Context parentContext, ReadWriteSpan span) { /* no-op */ }
        @Override public boolean isStartRequired() { return true; }
        @Override public void onEnd(ReadableSpan span) { /* no-op */ }
        @Override public boolean isEndRequired() { return true; }
        @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode forceFlush() { return CompletableResultCode.ofSuccess(); }
    }
}
