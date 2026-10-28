package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;

@Timeout(5)
class ExportBufferDelegatePendingTest {

    private static final long SCHEDULE_DELAY_NANOS = Duration.ofHours(1).toNanos();

    @Test
    void pendingDelegateBlocksTheNextBatchUntilRelease() throws InterruptedException {
        // given
        ExportBuffer buffer = new ExportBuffer(4, 1, new DropOldestExportMetrics());
        buffer.admit(spanData());
        SpanQueue.Batch timedOut = awaitBatch(buffer);
        buffer.admit(spanData());
        buffer.completeExport(timedOut, false, true);
        AtomicReference<WorkerAction> next = new AtomicReference<>();
        Thread waiter = new Thread(
                () -> next.set(buffer.awaitNextAction(SCHEDULE_DELAY_NANOS, System.nanoTime()))
        );

        // when
        waiter.start();

        // then
        assertThat(waiter.join(Duration.ofMillis(200))).isFalse();
        buffer.releaseDelegate();
        assertThat(waiter.join(Duration.ofSeconds(3))).isTrue();
        assertThat(next.get()).isInstanceOf(WorkerAction.ExportBatch.class);
    }

    @Test
    void timedOutBatchFailsTheFlushThatCoversIt() {
        // given
        ExportBuffer buffer = new ExportBuffer(4, 1, new DropOldestExportMetrics());
        buffer.admit(spanData());
        SpanQueue.Batch batch = awaitBatch(buffer);
        CompletableResultCode flush = buffer.requestFlush();

        // when
        buffer.completeExport(batch, false, true);

        // then
        assertThat(flush.isDone()).isTrue();
        assertThat(flush.isSuccess()).isFalse();
    }

    @Test
    void flushAdmittedWhileDelegateIsPendingWaitsForRelease() {
        // given
        ExportBuffer buffer = new ExportBuffer(4, 1, new DropOldestExportMetrics());
        buffer.admit(spanData());
        SpanQueue.Batch timedOut = awaitBatch(buffer);
        buffer.completeExport(timedOut, false, true);
        buffer.admit(spanData());
        CompletableResultCode flush = buffer.requestFlush();

        // when
        assertThat(flush.isDone()).isFalse();
        buffer.releaseDelegate();
        SpanQueue.Batch next = awaitBatch(buffer);
        buffer.completeExport(next, true, false);

        // then
        assertThat(flush.isSuccess()).isTrue();
    }

    @Test
    void overflowWhileDelegateIsPendingIsCounted() {
        // given
        DropOldestExportMetrics metrics = new DropOldestExportMetrics();
        ExportBuffer buffer = new ExportBuffer(2, 1, metrics);
        buffer.admit(spanData());
        SpanQueue.Batch timedOut = awaitBatch(buffer);
        buffer.admit(spanData());
        buffer.completeExport(timedOut, false, true);
        buffer.admit(spanData());

        // when
        buffer.admit(spanData());

        // then
        assertThat(metrics.droppedOverflowTotal()).isEqualTo(1L);
    }

    @Test
    void shutdownWithQueuedSpansAndPendingDelegateExpiresTheDrain() {
        // given
        AtomicLong nanoTime = new AtomicLong();
        ExportBuffer buffer = new ExportBuffer(4, 1, new DropOldestExportMetrics(), nanoTime::get);
        buffer.admit(spanData());
        SpanQueue.Batch timedOut = awaitBatch(buffer);
        buffer.completeExport(timedOut, false, true);
        buffer.admit(spanData());
        buffer.beginShutdown(10L);

        // when
        nanoTime.set(10L);
        WorkerAction action = buffer.awaitNextAction(SCHEDULE_DELAY_NANOS, nanoTime.get());

        // then
        assertThat(action).isEqualTo(new WorkerAction.StopWorker(WorkerStopReason.DRAIN_DEADLINE_EXCEEDED));
    }

    @Test
    void pendingDelegateWaitsTheFullScheduleDelayWhateverIsQueued() {
        // given
        long scheduleDelayNanos = Duration.ofSeconds(5).toNanos();
        long elapsedNanos = Duration.ofHours(1).toNanos();

        // when
        long pendingWait = ExportBuffer.idleWaitNanos(true, false, scheduleDelayNanos, elapsedNanos);
        long overdueWait = ExportBuffer.idleWaitNanos(false, false, scheduleDelayNanos, elapsedNanos);
        long partialWait = ExportBuffer.idleWaitNanos(false, false, scheduleDelayNanos, Duration.ofSeconds(2).toNanos());
        long emptyWait = ExportBuffer.idleWaitNanos(false, true, scheduleDelayNanos, elapsedNanos);

        // then
        assertThat(pendingWait).isEqualTo(scheduleDelayNanos);
        assertThat(overdueWait).isEqualTo(Duration.ofMillis(1).toNanos());
        assertThat(partialWait).isEqualTo(Duration.ofSeconds(3).toNanos());
        assertThat(emptyWait).isEqualTo(scheduleDelayNanos);
    }

    @Test
    void admitAndFlushDoNotWakeTheWorkerWhileDelegateIsPending() throws InterruptedException {
        // given
        AtomicInteger clockReads = new AtomicInteger();
        ExportBuffer buffer = new ExportBuffer(4, 1, new DropOldestExportMetrics(), () -> {
            clockReads.incrementAndGet();
            return System.nanoTime();
        });
        buffer.admit(spanData());
        SpanQueue.Batch timedOut = awaitBatch(buffer);
        buffer.completeExport(timedOut, false, true);
        Thread waiter = new Thread(() -> buffer.awaitNextAction(SCHEDULE_DELAY_NANOS, System.nanoTime()));
        waiter.start();
        awaitTimedWaiting(waiter);
        int readsBefore = clockReads.get();

        // when
        for (int index = 0; index < 100; index++) {
            buffer.admit(spanData());
        }
        CompletableResultCode flush = buffer.requestFlush();
        awaitTimedWaiting(waiter);

        // then
        assertThat(clockReads).hasValue(readsBefore);
        assertThat(flush.isDone()).isFalse();
        buffer.releaseDelegate();
        assertThat(waiter.join(Duration.ofSeconds(3))).isTrue();
    }

    @Test
    void elapsedScheduleDelayWithPendingDelegateReportsTheHold() {
        // given
        ExportBuffer buffer = new ExportBuffer(4, 1, new DropOldestExportMetrics());
        buffer.admit(spanData());
        SpanQueue.Batch timedOut = awaitBatch(buffer);
        buffer.admit(spanData());
        buffer.completeExport(timedOut, false, true);
        long scheduleDelayNanos = Duration.ofMillis(20).toNanos();

        // when
        WorkerAction action = buffer.awaitNextAction(scheduleDelayNanos, System.nanoTime());

        // then
        assertThat(action).isInstanceOfSatisfying(
                WorkerAction.DelegateHeld.class,
                held -> assertThat(held.heldNanos()).isGreaterThanOrEqualTo(scheduleDelayNanos)
        );
    }

    private static void awaitTimedWaiting(Thread thread) throws InterruptedException {
        while (thread.getState() != Thread.State.TIMED_WAITING) {
            Thread.sleep(1L);
        }
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
}
