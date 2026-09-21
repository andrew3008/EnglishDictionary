package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

class SpanTimeoutProcessorTest {

    @Test
    void force_closes_span_on_timeout_and_sets_platform_timeout_attribute() {
        // given
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        AtomicLong clock = new AtomicLong();
        SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofMillis(50), Duration.ofMinutes(1), clock::get, 10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanTimeout)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()) {
            Tracer tracer = sdk.getTracer("test");

            // when
            Span longSpan = tracer.spanBuilder("hung-op").startSpan();
            clock.addAndGet(Duration.ofMillis(51).toNanos());

            assertThat(spanTimeout.forceFlush().isSuccess()).isTrue();

            // then
            assertThat(exporter.getFinishedSpanItems()).hasSize(1);
            SpanData data = exporter.getFinishedSpanItems().getFirst();
            assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TIMEOUT))).isEqualTo("span");
            assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT))).isEqualTo("timeout");
            assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
            assertThat(spanTimeout.getForcedSpanEndAttemptsTotal()).isEqualTo(1);

            longSpan.end();
            assertThat(exporter.getFinishedSpanItems()).hasSize(1);
            spanTimeout.shutdown();
            tracerProvider.shutdown();
        }
    }

    @Test
    void does_not_touch_span_finished_before_timeout() {
        // given
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofMinutes(1), Duration.ofMinutes(1), System::nanoTime,
                10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanTimeout)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()) {
            Tracer tracer = sdk.getTracer("test");

            // when
            tracer.spanBuilder("fast-op").startSpan().end();
            assertThat(spanTimeout.forceFlush().isSuccess()).isTrue();

            // then
            SpanData data = exporter.getFinishedSpanItems().getFirst();
            assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TIMEOUT))).isNull();
            assertThat(spanTimeout.getForcedSpanEndAttemptsTotal()).isZero();

            spanTimeout.shutdown();
            tracerProvider.shutdown();
        }
    }

    @Test
    void force_closes_span_on_trace_timeout() {
        // given
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        AtomicLong clock = new AtomicLong();
        SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofMinutes(1), Duration.ofMillis(50), clock::get, 10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanTimeout)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()) {
            Tracer tracer = sdk.getTracer("test");

            // when
            Span span = tracer.spanBuilder("trace-root").startSpan();
            clock.addAndGet(Duration.ofMillis(51).toNanos());
            assertThat(spanTimeout.forceFlush().isSuccess()).isTrue();

            // then
            SpanData data = exporter.getFinishedSpanItems().getFirst();
            assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TIMEOUT))).isEqualTo("trace");
            assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT))).isEqualTo("timeout");
            assertThat(spanTimeout.getForcedTraceEndAttemptsTotal()).isEqualTo(1);

            span.end();
            spanTimeout.shutdown();
            tracerProvider.shutdown();
        }
    }

    @Test
    void forceFlush_closes_stuck_spans_synchronously() {
        // given
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        AtomicLong clock = new AtomicLong();
        SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofMillis(10), Duration.ofMinutes(1), clock::get, 10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanTimeout)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()) {
            Tracer tracer = sdk.getTracer("test");

            // when
            tracer.spanBuilder("hung-on-shutdown").startSpan();
            clock.addAndGet(Duration.ofMillis(11).toNanos());

            spanTimeout.forceFlush();

            // then
            assertThat(exporter.getFinishedSpanItems()).hasSize(1);
            SpanData data = exporter.getFinishedSpanItems().getFirst();
            assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
            assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TIMEOUT))).isEqualTo("span");
            assertThat(spanTimeout.getForcedSpanEndAttemptsTotal()).isEqualTo(1);

            spanTimeout.shutdown();
            tracerProvider.shutdown();
        }
    }

    @Test
    void concurrency_all_internal_indexes_cleared_after_spans_finish() throws InterruptedException {
        // given
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofMinutes(10), Duration.ofMinutes(10), System::nanoTime,
                10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanTimeout)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        int threads = 8;
        int spansPerThread = 1000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
                ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            Tracer tracer = sdk.getTracer("test");

            // when
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

            // then
            assertThat(spanTimeout.getActiveSpanCount()).isZero();
            assertThat(spanTimeout.getActiveTraceCount()).isZero();
            assertThat(spanTimeout.getForcedSpanEndAttemptsTotal()).isZero();
            assertThat(spanTimeout.getForcedTraceEndAttemptsTotal()).isZero();

            spanTimeout.shutdown();
            tracerProvider.shutdown();
        }
    }
}
