package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

@Timeout(5)
class ExportBufferTest {

    private static final long SCHEDULE_DELAY_NANOS = Duration.ofHours(1).toNanos();

    @Test
    void completedDrainWinsWhenWorkerObservesItAfterDeadline() {
        // given
        AtomicLong nanoTime = new AtomicLong(10L);
        ExportBuffer buffer = new ExportBuffer(4, 2, new DropOldestExportMetrics(), nanoTime::get);
        buffer.beginShutdown(5L);

        // when
        nanoTime.set(20L);
        WorkerAction action =
                buffer.awaitNextAction(SCHEDULE_DELAY_NANOS, nanoTime.get());

        // then
        assertThat(action).isEqualTo(
                new WorkerAction.StopWorker(WorkerStopReason.DRAIN_COMPLETED)
        );
    }

    @Test
    void shutdownDeadlineSurvivesNanoTimeOverflow() {
        // given
        AtomicLong nanoTime = new AtomicLong(Long.MAX_VALUE - 5L);
        DropOldestExportMetrics metrics = new DropOldestExportMetrics();
        ExportBuffer buffer = new ExportBuffer(4, 2, metrics, nanoTime::get);
        buffer.admit(spanData());
        buffer.beginShutdown(10L);

        // when
        nanoTime.set(Long.MIN_VALUE + 5L);
        WorkerAction action =
                buffer.awaitNextAction(SCHEDULE_DELAY_NANOS, nanoTime.get());

        // then
        assertThat(action).isEqualTo(
                new WorkerAction.StopWorker(WorkerStopReason.DRAIN_DEADLINE_EXCEEDED)
        );
        assertThat(metrics.discardedQueuedTotal()).isEqualTo(1L);
    }

    @Test
    void exportConfirmedAtShutdownDeadlineFailsTheDrain() {
        // given
        AtomicLong nanoTime = new AtomicLong();
        ExportBuffer buffer = new ExportBuffer(
                4,
                1,
                new DropOldestExportMetrics(),
                nanoTime::get
        );
        buffer.admit(spanData());
        SpanQueue.Batch batch = awaitBatch(buffer);
        CompletableResultCode flush = buffer.requestFlush();
        buffer.beginShutdown(10L);

        // when
        nanoTime.set(10L);
        buffer.completeExport(batch, true, false);
        WorkerAction action = buffer.awaitNextAction(SCHEDULE_DELAY_NANOS, nanoTime.get());

        // then
        assertThat(action).isEqualTo(
                new WorkerAction.StopWorker(WorkerStopReason.DRAIN_DEADLINE_EXCEEDED)
        );
        assertThat(flush.isDone()).isTrue();
        assertThat(flush.isSuccess()).isFalse();
    }

    @Test
    void exporterShutdownCannotRetroactivelyExpireCompletedDrain() {
        // given
        AtomicLong nanoTime = new AtomicLong();
        long timeoutNanos = 10L;
        DropOldestExportMetrics metrics = new DropOldestExportMetrics();
        ExportBuffer buffer = new ExportBuffer(4, 2, metrics, nanoTime::get);
        SpanExporter delegate = new ClockAdvancingShutdownExporter(nanoTime, timeoutNanos + 1L);
        TimedSpanExporter exporter =
                new TimedSpanExporter(delegate, timeoutNanos, timeoutNanos, metrics);
        ExportWorker worker = new ExportWorker(
                buffer,
                exporter,
                SCHEDULE_DELAY_NANOS,
                timeoutNanos
        );

        // when
        CompletableResultCode shutdown = worker.shutdown();
        worker.run();

        // then
        assertThat(shutdown.isSuccess()).isTrue();
        assertThat(buffer.lifecycleState()).isEqualTo(LifecycleState.TERMINATED);
        assertThat(nanoTime.get()).isGreaterThan(timeoutNanos);
    }

    @Test
    void staleExportCompletionCannotFailAnotherFlushGeneration() {
        // given
        ExportBuffer buffer = new ExportBuffer(4, 1, new DropOldestExportMetrics());
        buffer.admit(spanData());
        SpanQueue.Batch firstBatch = awaitBatch(buffer);
        buffer.completeExport(firstBatch, true, false);
        buffer.admit(spanData());
        SpanQueue.Batch secondBatch = awaitBatch(buffer);
        CompletableResultCode secondFlush = buffer.requestFlush();

        // when
        assertThatThrownBy(() -> buffer.completeExport(firstBatch, false, false))
                .isInstanceOf(IllegalStateException.class);

        // then
        assertThat(secondFlush.isDone()).isFalse();
        buffer.completeExport(secondBatch, true, false);
        assertThat(secondFlush.isSuccess()).isTrue();
    }

    @Test
    void flushCallbackRunsWithoutTheBufferLock() throws InterruptedException {
        // given
        ExportBuffer buffer = new ExportBuffer(4, 1, new DropOldestExportMetrics());
        buffer.admit(spanData());

        SpanQueue.Batch batch = awaitBatch(buffer);
        CompletableResultCode flush = buffer.requestFlush();

        CountDownLatch reentered = new CountDownLatch(1);
        AtomicBoolean callbackObservedReentry = new AtomicBoolean();
        AtomicReference<Thread> probeThread = new AtomicReference<>();
        flush.whenComplete(() -> {
            Thread probe = new Thread(() -> {
                buffer.snapshot();
                reentered.countDown();
            });
            probeThread.set(probe);
            probe.start();

            try {
                callbackObservedReentry.set(reentered.await(3L, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });

        // when
        buffer.completeExport(batch, true, false);

        // then
        probeThread.get().join(TimeUnit.SECONDS.toMillis(3L));
        assertThat(callbackObservedReentry).isTrue();
        assertThat(probeThread.get().isAlive()).isFalse();
    }

    @Test
    void callbackFailureCannotStrandAnotherTerminalFlushGeneration() {
        // given
        ExportBuffer buffer = new ExportBuffer(4, 1, new DropOldestExportMetrics());
        buffer.admit(spanData());

        SpanQueue.Batch batch = awaitBatch(buffer);
        CompletableResultCode active = buffer.requestFlush();
        CompletableResultCode queued = buffer.requestFlush();
        active.whenComplete(() -> {
            throw new IllegalStateException("simulated callback failure");
        });

        // when
        buffer.completeExport(batch, true, false);

        // then
        assertThat(active.isSuccess()).isTrue();
        assertThat(queued.isSuccess()).isTrue();
        assertThat(buffer.lifecycleState()).isEqualTo(LifecycleState.ACTIVE);
    }

    @Test
    void interruptedWaitFailsTheBufferAndRestoresTheInterruptFlag() {
        // given
        AtomicLong nanoTime = new AtomicLong();
        DropOldestExportMetrics metrics = new DropOldestExportMetrics();
        ExportBuffer buffer = new ExportBuffer(4, 2, metrics, nanoTime::get);
        buffer.admit(spanData());

        try {
            // when
            Thread.currentThread().interrupt();
            WorkerAction action =
                    buffer.awaitNextAction(SCHEDULE_DELAY_NANOS, nanoTime.get());

            // then
            assertThat(action).isEqualTo(
                    new WorkerAction.StopWorker(WorkerStopReason.WORKER_FAILED)
            );
            assertThat(buffer.lifecycleState()).isEqualTo(LifecycleState.FAILED);
            assertThat(metrics.discardedQueuedTotal()).isEqualTo(1L);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            boolean wasInterrupted = Thread.interrupted();
            assertThat(wasInterrupted).isTrue();
        }
    }

    @Test
    void markTerminatedFromActiveFailsPendingFlushAndDiscardsQueue() {
        // given
        DropOldestExportMetrics metrics = new DropOldestExportMetrics();
        ExportBuffer buffer = new ExportBuffer(4, 2, metrics);
        buffer.admit(spanData());
        CompletableResultCode flush = buffer.requestFlush();

        // when
        boolean failed = buffer.markTerminated();

        // then
        assertThat(failed).isTrue();
        assertThat(buffer.lifecycleState()).isEqualTo(LifecycleState.FAILED);
        assertThat(flush.isDone()).isTrue();
        assertThat(flush.isSuccess()).isFalse();
        assertThat(buffer.snapshot().queueSize()).isZero();
        assertThat(metrics.discardedQueuedTotal()).isEqualTo(1L);
    }

    private static SpanQueue.Batch awaitBatch(ExportBuffer buffer) {
        WorkerAction action = buffer.awaitNextAction(SCHEDULE_DELAY_NANOS, System.nanoTime());
        assertThat(action).isInstanceOf(WorkerAction.ExportBatch.class);
        return ((WorkerAction.ExportBatch) action).batch();
    }

    private static SpanData spanData() {
        SpanData spanData = Mockito.mock(SpanData.class);
        Mockito.when(spanData.getAttributes()).thenReturn(Attributes.empty());
        return spanData;
    }

    private record ClockAdvancingShutdownExporter(AtomicLong nanoTime,
                                                  long shutdownAdvanceNanos) implements SpanExporter {

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            nanoTime.addAndGet(shutdownAdvanceNanos);
            return CompletableResultCode.ofSuccess();
        }
    }
}
