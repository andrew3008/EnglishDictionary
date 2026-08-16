package space.br1440.platform.tracing.otel.javaagent.processor;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class SpanWatchdogProcessorTest {

    @Test
    void force_closes_span_on_timeout_and_sets_platform_timeout_attribute() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        AtomicLong clock = new AtomicLong();
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofMillis(50), Duration.ofMinutes(1), clock::get);

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(watchdog)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("test");

        Span longSpan = tracer.spanBuilder("hung-op").startSpan();
        clock.addAndGet(Duration.ofMillis(51).toNanos());

        watchdog.scanForTesting();

        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        var data = exporter.getFinishedSpanItems().get(0);
        assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TIMEOUT))).isEqualTo("span");
        assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT))).isEqualTo("timeout");
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(watchdog.getForcedSpanCloses()).isEqualTo(1);

        // Повторный end() от приложения не должен ломать логику.
        longSpan.end();
        watchdog.shutdown();
        tracerProvider.shutdown();
    }

    @Test
    void does_not_touch_span_finished_before_timeout() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(Duration.ofMinutes(1), Duration.ofMinutes(1), System::nanoTime);

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(watchdog)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("test");

        tracer.spanBuilder("fast-op").startSpan().end();
        watchdog.scanForTesting();

        var data = exporter.getFinishedSpanItems().get(0);
        assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TIMEOUT))).isNull();
        assertThat(watchdog.getForcedSpanCloses()).isZero();

        watchdog.shutdown();
        tracerProvider.shutdown();
    }

    @Test
    void force_closes_span_on_trace_timeout() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        AtomicLong clock = new AtomicLong();
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofMinutes(1), Duration.ofMillis(50), clock::get);

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(watchdog)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("test");

        Span span = tracer.spanBuilder("trace-root").startSpan();
        clock.addAndGet(Duration.ofMillis(51).toNanos());
        watchdog.scanForTesting();

        var data = exporter.getFinishedSpanItems().get(0);
        assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TIMEOUT))).isEqualTo("trace");
        assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT))).isEqualTo("timeout");
        assertThat(watchdog.getForcedTraceCloses()).isEqualTo(1);

        span.end();
        watchdog.shutdown();
        tracerProvider.shutdown();
    }

    @Test
    void forceFlush_closes_stuck_spans_synchronously() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        AtomicLong clock = new AtomicLong();
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofMillis(10), Duration.ofMinutes(1), clock::get);

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(watchdog)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("test");

        tracer.spanBuilder("hung-on-shutdown").startSpan();
        clock.addAndGet(Duration.ofMillis(11).toNanos());

        watchdog.forceFlush();

        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        var data = exporter.getFinishedSpanItems().get(0);
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TIMEOUT))).isEqualTo("span");
        assertThat(watchdog.getForcedSpanCloses()).isEqualTo(1);

        watchdog.shutdown();
        tracerProvider.shutdown();
    }

    @Test
    void concurrency_all_internal_indexes_cleared_after_spans_finish() throws InterruptedException {
        // Регрессионный тест на BUG-3 + BUG-4: после одновременного завершения всех span'ов
        // единый trace-owned state должен полностью освободиться.
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofMinutes(10), Duration.ofMinutes(10), System::nanoTime);

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(watchdog)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("test");

        int threads = 8;
        int spansPerThread = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < spansPerThread; i++) {
                        Span s = tracer.spanBuilder("op-" + i).startSpan();
                        s.end();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(watchdog.getActiveSpanCount()).isZero();
        assertThat(watchdog.getActiveTraceCount()).isZero();
        assertThat(watchdog.getForcedSpanCloses()).isZero();
        assertThat(watchdog.getForcedTraceCloses()).isZero();

        watchdog.shutdown();
        tracerProvider.shutdown();
    }
}
