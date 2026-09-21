package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

class SpanTimeoutSchedulerLiveTest {

    @Test
    void realSchedulerForceClosesHungSpanAndStopsAfterShutdown() {
        // given
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofMillis(80), Duration.ofSeconds(2), Duration.ofMillis(20), 10_000,
                Integer.MAX_VALUE, Integer.MAX_VALUE);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanTimeout)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("scheduler");

        // when
        Span hung = tracer.spanBuilder("hung").startSpan();
        awaitCondition(
                Duration.ofSeconds(2),
                () -> !exporter.getFinishedSpanItems().isEmpty() && spanTimeout.getForcedSpanCloses() == 1L);

        // then
        assertThat(exporter.getFinishedSpanItems()).isNotEmpty();
        assertThat(spanTimeout.getForcedSpanCloses()).isEqualTo(1);
        Thread spanTimeoutThread = threadNamed("platform-tracing-watchdog");
        assertThat(spanTimeoutThread).isNotNull();
        assertThat(spanTimeoutThread.isDaemon()).isTrue();

        assertThat(spanTimeout.shutdown().isSuccess()).isTrue();
        int afterShutdown = exporter.getFinishedSpanItems().size();
        Span another = tracer.spanBuilder("after-shutdown").startSpan();
        sleep(Duration.ofMillis(200));
        assertThat(exporter.getFinishedSpanItems()).hasSize(afterShutdown);
        another.end();
        hung.end();
        tracerProvider.shutdown();
    }

    @Test
    void scanContinuesAfterRecoverableException() {
        // given
        AtomicLong clock = new AtomicLong();
        AtomicInteger failNextScan = new AtomicInteger(1);
        SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofMillis(10), Duration.ofMinutes(1), () -> {
                    if (failNextScan.get() == 0) {
                        throw new IllegalStateException("injected scan failure");
                    }
                    return clock.get();
                }, 10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanTimeout)
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("scan");
        Span hung = tracer.spanBuilder("hung").startSpan();
        failNextScan.set(0);

        // when
        spanTimeout.scanForTesting();
        failNextScan.set(-1);
        clock.set(Duration.ofMillis(20).toNanos());
        spanTimeout.scanForTesting();

        // then
        assertThat(spanTimeout.getForcedSpanCloses()).isEqualTo(1);
        hung.end();
        spanTimeout.shutdown();
        tracerProvider.shutdown();
    }

    private static Thread threadNamed(String name) {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (name.equals(thread.getName())) {
                return thread;
            }
        }
        return null;
    }

    private static void awaitCondition(Duration timeout, BooleanSupplier condition) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(Duration.ofMillis(10));
        }
    }

    private static void sleep(Duration duration) {
        try {
            TimeUnit.NANOSECONDS.sleep(duration.toNanos());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
