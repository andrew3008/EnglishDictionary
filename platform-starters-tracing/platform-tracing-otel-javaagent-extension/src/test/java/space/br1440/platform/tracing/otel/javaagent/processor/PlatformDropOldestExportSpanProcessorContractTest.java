package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.processor.dropoldest.PlatformDropOldestExportSpanProcessor;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class PlatformDropOldestExportSpanProcessorContractTest {

    @RegisterExtension
    AgentLogCapture capture = new AgentLogCapture();

    private Thread.UncaughtExceptionHandler previousDefaultHandler;

    @BeforeEach
    void suppressExpectedUncaughtErrorNoise() {
        // given
        previousDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> { });
    }

    @AfterEach
    void restoreDefaultUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler(previousDefaultHandler);
    }

    @Test
    void backgroundScheduleDelayExportsWithoutExplicitForceFlush() {
        // given
        CountingExporter exporter = new CountingExporter();
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(exporter)
                .maxQueueSize(64)
                .maxExportBatchSize(64)
                .scheduleDelay(Duration.ofMillis(50))
                .build();
        try (SdkTracerProvider tp = provider(processor)) {
            Tracer tracer = tp.get("schedule-delay-test");
            tracer.spanBuilder("scheduled").startSpan().end();

            // when
            // then
            awaitTrue(() -> exporter.exported.size() == 1, Duration.ofSeconds(2),
                    "background scheduleDelay export did not happen");
        }
    }

    @Test
    void forceFlushDuringConcurrentShutdownStillCompletes() {
        // given
        CountingExporter exporter = new CountingExporter();
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(exporter)
                .maxQueueSize(64)
                .maxExportBatchSize(8)
                .scheduleDelay(Duration.ofMinutes(10))
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();
        SdkTracerProvider tp = provider(processor);
        Tracer tracer = tp.get("concurrent-flush-shutdown-test");
        for (int i = 0; i < 5; i++) {
            tracer.spanBuilder("s-" + i).startSpan().end();
        }

        ExecutorService racers = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch startSignal = new CountDownLatch(1);

            Future<CompletableResultCode> flushFuture = racers.submit(() -> {
                await(startSignal);
                return processor.forceFlush();
            });

            Future<CompletableResultCode> shutdownFuture = racers.submit(() -> {
                await(startSignal);
                return processor.shutdown();
            });

            startSignal.countDown();

            // when
            CompletableResultCode flush = flushFuture.get(5, TimeUnit.SECONDS);
            CompletableResultCode shutdown = shutdownFuture.get(5, TimeUnit.SECONDS);

            // then
            assertThat(flush.join(5, TimeUnit.SECONDS).isDone()).isTrue();
            assertThat(shutdown.join(5, TimeUnit.SECONDS).isDone()).isTrue();
            assertThat(shutdown.isSuccess()).isTrue();
        } catch (Exception e) {
            throw new AssertionError("concurrent forceFlush/shutdown race failed", e);
        } finally {
            racers.shutdownNow();
            tp.close();
        }
    }

    @Test
    void manyParallelForceFlushCallsAllCompleteWithoutHangOrException() {
        // given
        CountingExporter exporter = new CountingExporter();
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(exporter)
                .maxQueueSize(256)
                .maxExportBatchSize(8)
                .scheduleDelay(Duration.ofMinutes(10))
                .build();
        SdkTracerProvider tp = provider(processor);
        Tracer tracer = tp.get("parallel-force-flush-test");
        for (int i = 0; i < 40; i++) {
            tracer.spanBuilder("p-" + i).startSpan().end();
        }

        int racerCount = 8;
        ExecutorService racers = Executors.newFixedThreadPool(racerCount);
        try {
            CountDownLatch startSignal = new CountDownLatch(1);
            List<Future<CompletableResultCode>> futures = new ArrayList<>();
            for (int i = 0; i < racerCount; i++) {
                futures.add(racers.submit(() -> {
                    await(startSignal);
                    return processor.forceFlush();
                }));
            }
            startSignal.countDown();

            // when
            // then
            for (Future<CompletableResultCode> future : futures) {
                CompletableResultCode result = future.get(5, TimeUnit.SECONDS);
                assertThat(result.join(5, TimeUnit.SECONDS).isSuccess())
                        .as("each racing forceFlush eventually succeeds")
                        .isTrue();
            }
            assertThat(exporter.exported).hasSize(40);
        } catch (Exception e) {
            throw new AssertionError("parallel forceFlush race failed", e);
        } finally {
            racers.shutdownNow();
            tp.close();
        }
    }

    @Test
    void toSpanDataFailureIsIsolatedAndSpanIsSilentlyDropped() {
        // given
        CountingExporter exporter = new CountingExporter();
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(exporter)
                .maxQueueSize(16)
                .maxExportBatchSize(4)
                .scheduleDelay(Duration.ofMillis(50))
                .build();
        try {
            ReadableSpan poisoned = mock(ReadableSpan.class);
            when(poisoned.getSpanContext()).thenReturn(SpanContext.create(
                    "00000000000000000000000000000001", "0000000000000001",
                    TraceFlags.getSampled(), TraceState.getDefault()));
            when(poisoned.toSpanData()).thenThrow(new RuntimeException("simulated toSpanData failure"));

            // when
            processor.onEnd(poisoned);

            // then
            assertThat(processor.metricsSnapshot().queueSize()).isZero();
            assertThat(processor.metricsSnapshot().droppedOverflowTotal()).isZero();
            assertThat(processor.metricsSnapshot().droppedAfterShutdownTotal()).isZero();
        } finally {
            processor.shutdown().join(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void forceFlushAfterCompletedShutdownReturnsNewIndependentFailureResult() {
        // given
        CountingExporter exporter = new CountingExporter();
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(exporter)
                .build();
        CompletableResultCode shutdown = processor.shutdown();
        shutdown.join(5, TimeUnit.SECONDS);
        assertThat(shutdown.isDone()).isTrue();

        // when
        CompletableResultCode flushAfterShutdown = processor.forceFlush();

        // then
        assertThat(flushAfterShutdown).isNotSameAs(shutdown);
        assertThat(flushAfterShutdown.isDone()).isTrue();
        assertThat(flushAfterShutdown.isSuccess()).isFalse();
    }

    @Test
    void nonRuntimeExceptionExporterFailureIsAlreadyIsolatedInsideExportBatch() {
        // given
        AtomicInteger calls = new AtomicInteger();
        SpanExporter throwsRuntimeExceptionOnce = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> batch) {
                if (calls.incrementAndGet() == 1) {
                    throw new RuntimeException("simulated single export failure");
                }
                return CompletableResultCode.ofSuccess();
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        };

        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(throwsRuntimeExceptionOnce)
                .maxQueueSize(16)
                .maxExportBatchSize(1)
                .scheduleDelay(Duration.ofMillis(50))
                .build();
        try (SdkTracerProvider tp = provider(processor)) {
            Tracer tracer = tp.get("runtime-exception-isolation-test");
            tracer.spanBuilder("first").startSpan().end();
            tracer.spanBuilder("second").startSpan().end();

            // when
            CompletableResultCode flush = processor.forceFlush();
            assertThat(flush.join(5, TimeUnit.SECONDS).isSuccess()).isFalse();

            // then
            awaitTrue(() -> calls.get() >= 2, Duration.ofSeconds(2),
                    "worker did not continue exporting after an isolated RuntimeException");
            assertThat(processor.metricsSnapshot().failuresTotal()).isEqualTo(1);
        }
    }

    @Test
    void nonfatalErrorEscapingWorkerLoopTransitionsToFailedAndClosesAdmission() throws InterruptedException {
        // given
        CountDownLatch exportEntered = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        SpanExporter throwsErrorForever = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> batch) {
                calls.incrementAndGet();
                exportEntered.countDown();
                throw new AssertionError("simulated nonfatal Error escaping catch(RuntimeException)");
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        };

        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(throwsErrorForever)
                .maxQueueSize(16)
                .maxExportBatchSize(1)
                .scheduleDelay(Duration.ofMinutes(10))
                .build();
        try (SdkTracerProvider tp = provider(processor)) {
            Tracer tracer = tp.get("worker-death-test");
            tracer.spanBuilder("poisoned").startSpan().end();

            // when
            assertThat(exportEntered.await(5, TimeUnit.SECONDS)).isTrue();
            awaitTrue(() -> calls.get() >= 1, Duration.ofSeconds(2),
                    "expected at least one export attempt");
            awaitTrue(() -> processor.metricsSnapshot().failuresTotal() >= 1, Duration.ofSeconds(2),
                    "nonfatal Error escaping exporter.export() must be counted as a failure, not silently swallowed");

            // then
            tracer.spanBuilder("after-death").startSpan().end();
            awaitTrue(() -> calls.get() >= 2, Duration.ofSeconds(2),
                    "worker must keep retrying export after a nonfatal Error instead of dying silently");
            assertThat(processor.metricsSnapshot().lifecycleState()).isEqualTo("ACTIVE");
        }
    }

    @Test
    void forceFlushDuringNonfatalExporterErrorAlwaysCompletesWithFailure() throws InterruptedException {
        // given
        CountDownLatch exportEntered = new CountDownLatch(1);
        SpanExporter throwsErrorForever = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> batch) {
                exportEntered.countDown();
                throw new AssertionError("simulated nonfatal Error escaping catch(RuntimeException)");
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        };

        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(throwsErrorForever)
                .maxQueueSize(16)
                .maxExportBatchSize(1)
                .scheduleDelay(Duration.ofMinutes(10))
                .build();

        try (SdkTracerProvider tp = provider(processor)) {
            Tracer tracer = tp.get("stuck-flush-promise-test");
            tracer.spanBuilder("poisoned").startSpan().end();

            // when
            CompletableResultCode flush = processor.forceFlush();
            assertThat(exportEntered.await(5, TimeUnit.SECONDS)).isTrue();

            // then
            CompletableResultCode joined = flush.join(5, TimeUnit.SECONDS);
            assertThat(joined.isDone())
                    .as("flush promise must complete even when the batch export fails with a nonfatal Error")
                    .isTrue();
            assertThat(joined.isSuccess()).isFalse();
        }
    }

    @Test
    void oneFailedExportBatchNoLongerPoisonsFutureShutdownResult() {
        // given
        AtomicInteger calls = new AtomicInteger();
        SpanExporter failsOnceThenAlwaysSucceeds = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> batch) {
                if (calls.incrementAndGet() == 1) {
                    throw new RuntimeException("simulated single export failure");
                }
                return CompletableResultCode.ofSuccess();
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        };

        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(failsOnceThenAlwaysSucceeds)
                .maxQueueSize(16)
                .maxExportBatchSize(1)
                .scheduleDelay(Duration.ofMillis(50))
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();
        SdkTracerProvider tp = provider(processor);
        Tracer tracer = tp.get("sticky-worker-failure-test");
        tracer.spanBuilder("fails").startSpan().end();
        tracer.spanBuilder("succeeds").startSpan().end();
        processor.forceFlush().join(5, TimeUnit.SECONDS);
        awaitTrue(() -> processor.metricsSnapshot().queueSize() == 0, Duration.ofSeconds(2),
                "queue did not drain before shutdown");

        // when
        CompletableResultCode shutdown = tp.shutdown();
        shutdown.join(5, TimeUnit.SECONDS);

        assertThat(shutdown.isSuccess())
                .as("a single historical export failure must not poison an unrelated later shutdown() outcome")
                .isTrue();
    }

    @Test
    void exportFailureLogThrottleRecoversViaRateLimitedLoggerInsteadOfPermanentOneShot() {
        // given
        SpanExporter alwaysThrows = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> batch) {
                throw new RuntimeException("simulated permanent export failure");
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        };

        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(alwaysThrows)
                .maxQueueSize(32)
                .maxExportBatchSize(1)
                .scheduleDelay(Duration.ofMillis(20))
                .build();

        try (SdkTracerProvider tp = provider(processor)) {
            Tracer tracer = tp.get("log-throttle-test");
            for (int i = 0; i < 10; i++) {
                tracer.spanBuilder("f-" + i).startSpan().end();
                processor.forceFlush().join(2, TimeUnit.SECONDS);
            }

            long exportFailureWarnCount = capture.events().stream()
                    .filter(event -> event.level() == AgentLogLevel.WARN)
                    .filter(event -> AgentEventCode.EXPORT_TIMED.equals(event.eventCode()))
                    .count();
            assertThat(exportFailureWarnCount)
                    .as("RateLimitedLogger throttles bursts but is not a single permanent one-shot flag")
                    .isGreaterThan(1)
                    .isLessThan(10);
            assertThat(processor.metricsSnapshot().failuresTotal())
                    .as("counter keeps growing on every export failure regardless of log throttling")
                    .isEqualTo(10);
        }
    }

    private static SdkTracerProvider provider(PlatformDropOldestExportSpanProcessor processor) {
        return SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(processor)
                .build();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("start signal was not released in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for start signal", e);
        }
    }

    private static void awaitTrue(BooleanSupplier condition, Duration timeout, String failureMessage) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }

            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }

        assertThat(condition.getAsBoolean()).as(failureMessage).isTrue();
    }

    private static final class CountingExporter implements SpanExporter {
        final List<SpanData> exported = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            exported.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
