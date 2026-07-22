package space.br1440.platform.tracing.bench;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.infra.Blackhole;
import space.br1440.platform.tracing.otel.javaagent.processor.PlatformDropOldestExportSpanProcessor;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * Микробюджет queue-offer пути экспорта (Фаза 17, PR-1; REQ-EXPORT-ASYNC-001,
 * инварианты P1/P2).
 * <p>
 * Измеряется стоимость {@code span.end()} → {@code onEnd} → постановка SpanData в
 * bounded-очередь для двух реализаций:
 * <ul>
 *   <li><b>stock BSP</b> (OTel SDK, drop-new при переполнении);</li>
 *   <li><b>{@code PlatformDropOldestExportSpanProcessor}</b> (BSP-lite, drop-oldest —
 *       требование §5; ADR-drop-oldest-export-processor-v1).</li>
 * </ul>
 * Оба варианта в двух режимах очереди:
 * <ul>
 *   <li><b>steady</b> — default capacity 2048, noop-экспортёр успевает дренировать:
 *       типичный production-путь (enqueue без вытеснения);</li>
 *   <li><b>saturated</b> — capacity 32, медленный экспортёр (никогда не завершает export):
 *       worst case постоянного переполнения — измеряет цену eviction/drop ветки.</li>
 * </ul>
 * {@code @Threads(4)} — контеншн на общей очереди, как у многопоточного HTTP-сервера.
 * Режим {@code SampleTime} даёт p99 — это и есть микробюджет инварианта P2
 * («queue-offer путь bounded по времени»).
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class QueueOfferBenchmark {

    private OpenTelemetrySdk bspSteadySdk;
    private OpenTelemetrySdk bspSaturatedSdk;
    private OpenTelemetrySdk dropOldestSteadySdk;
    private OpenTelemetrySdk dropOldestSaturatedSdk;

    private Tracer bspSteadyTracer;
    private Tracer bspSaturatedTracer;
    private Tracer dropOldestSteadyTracer;
    private Tracer dropOldestSaturatedTracer;

    @Setup(Level.Trial)
    public void setUp() {
        // Steady: noop-экспортёр мгновенно подтверждает export — очередь дренируется.
        bspSteadySdk = sdkWith(BatchSpanProcessor.builder(new NoopExporter()).build());
        bspSteadyTracer = bspSteadySdk.getTracer("bench");

        // Saturated: экспортёр никогда не завершает CompletableResultCode — очередь 32
        // заполняется мгновенно, каждый offer идёт по ветке переполнения (drop-new у BSP).
        bspSaturatedSdk = sdkWith(BatchSpanProcessor.builder(new StuckExporter())
                .setMaxQueueSize(32)
                .setScheduleDelay(Duration.ofMillis(10))
                .build());
        bspSaturatedTracer = bspSaturatedSdk.getTracer("bench");

        dropOldestSteadySdk = sdkWith(
                PlatformDropOldestExportSpanProcessor.builder(new NoopExporter()).build());
        dropOldestSteadyTracer = dropOldestSteadySdk.getTracer("bench");

        dropOldestSaturatedSdk = sdkWith(
                PlatformDropOldestExportSpanProcessor.builder(new StuckExporter())
                        .maxQueueSize(32)
                        .scheduleDelay(Duration.ofMillis(10))
                        .build());
        dropOldestSaturatedTracer = dropOldestSaturatedSdk.getTracer("bench");
    }

    private static OpenTelemetrySdk sdkWith(io.opentelemetry.sdk.trace.SpanProcessor processor) {
        return OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(processor)
                        .build())
                .build();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        // StuckExporter никогда не подтверждает export — closeQuietly с коротким ожиданием.
        for (OpenTelemetrySdk sdk : new OpenTelemetrySdk[]{
                bspSteadySdk, bspSaturatedSdk, dropOldestSteadySdk, dropOldestSaturatedSdk}) {
            sdk.getSdkTracerProvider().shutdown().join(2, TimeUnit.SECONDS);
        }
    }

    // -- Steady state: enqueue без переполнения -------------------------------------------

    @Benchmark
    @Threads(4)
    public void bspOfferSteady(Blackhole bh) {
        Span span = bspSteadyTracer.spanBuilder("bench-op").startSpan();
        span.end();
        bh.consume(span);
    }

    @Benchmark
    @Threads(4)
    public void dropOldestOfferSteady(Blackhole bh) {
        Span span = dropOldestSteadyTracer.spanBuilder("bench-op").startSpan();
        span.end();
        bh.consume(span);
    }

    // -- Saturated: каждый offer по ветке переполнения ------------------------------------

    @Benchmark
    @Threads(4)
    public void bspOfferSaturatedDropNew(Blackhole bh) {
        Span span = bspSaturatedTracer.spanBuilder("bench-op").startSpan();
        span.end();
        bh.consume(span);
    }

    @Benchmark
    @Threads(4)
    public void dropOldestOfferSaturatedEviction(Blackhole bh) {
        Span span = dropOldestSaturatedTracer.spanBuilder("bench-op").startSpan();
        span.end();
        bh.consume(span);
    }

    /** Мгновенно успешный экспортёр: изолирует enqueue-путь от транспорта. */
    private static final class NoopExporter implements SpanExporter {
        @Override public CompletableResultCode export(Collection<SpanData> spans) {
            return CompletableResultCode.ofSuccess();
        }
        @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }

    /**
     * «Зависший» экспортёр: export никогда не завершается — worker блокируется на первом
     * batch'е, очередь остаётся полной, offer стабильно идёт по ветке переполнения.
     */
    private static final class StuckExporter implements SpanExporter {
        @Override public CompletableResultCode export(Collection<SpanData> spans) {
            return new CompletableResultCode(); // намеренно не complete
        }
        @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }
}
