package space.br1440.platform.tracing.otel.javaagent.processor.watchdog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogEvent;

import io.opentelemetry.sdk.trace.ReadWriteSpan;

class WatchdogTraceIndexTest {

    private static final long HOUR_NANOS = Duration.ofHours(1).toNanos();

    private static WatchdogTraceIndex index(int maxTrackedTraces,
                                            int maxTrackedSpansPerTrace,
                                            SpanPermitTracker permitTracker,
                                            AtomicLong clock) {
        return new WatchdogTraceIndex(
                HOUR_NANOS, HOUR_NANOS,
                maxTrackedTraces, maxTrackedSpansPerTrace,
                clock::get, permitTracker
        );
    }

    private static WatchdogTraceIndex index(int maxTrackedTraces,
                                            SpanPermitTracker permitTracker,
                                            AtomicLong clock,
                                            Runnable afterFirstEvictionPlan) {
        return new WatchdogTraceIndex(
                HOUR_NANOS, HOUR_NANOS,
                maxTrackedTraces, 10,
                clock::get, permitTracker,
                afterFirstEvictionPlan
        );
    }

    @SuppressWarnings("SameParameterValue")
    private static WatchdogTraceIndex index(long spanTimeoutNanos,
                                            long traceTimeoutNanos,
                                            int maxTrackedTraces,
                                            SpanPermitTracker permitTracker,
                                            AtomicLong clock,
                                            Runnable afterFirstEvictionPlan) {
        return new WatchdogTraceIndex(
                spanTimeoutNanos, traceTimeoutNanos,
                maxTrackedTraces, 10,
                clock::get, permitTracker,
                afterFirstEvictionPlan
        );
    }

    @Test
    void duplicateSpanIdIsNoOpAndDoesNotConsumeAnExtraPermit() {
        // given
        SpanPermitTracker permitTracker = new SpanPermitTracker(5);
        AtomicLong clock = new AtomicLong();
        WatchdogTraceIndex index = index(5, 5, permitTracker, clock);
        ReadWriteSpan span = WatchdogTestSpans.span(1, 1);

        // when
        index.admit("t1", "s1", span, clock.get(), () -> true);
        index.admit("t1", "s1", span, clock.get(), () -> true);

        // then
        assertThat(index.trackedSpanCount()).isEqualTo(1);
        assertThat(index.rejectedPerTraceTotal()).isZero();
        assertThat(index.rejectedGlobalTotal()).isZero();
    }

    @Test
    void perTraceSaturationRejectsSecondDistinctSpanOnSameTrace() {
        // given
        SpanPermitTracker permitTracker = new SpanPermitTracker(100);
        AtomicLong clock = new AtomicLong();
        WatchdogTraceIndex index = index(100, 1, permitTracker, clock);

        // when
        index.admit("t1", "s1", WatchdogTestSpans.span(1, 1), clock.get(), () -> true);
        index.admit("t1", "s2", WatchdogTestSpans.span(1, 2), clock.get(), () -> true);

        // then
        assertThat(index.rejectedPerTraceTotal()).isEqualTo(1);
        assertThat(index.rejectedGlobalTotal()).isZero();
        assertThat(index.trackedSpanCount()).isEqualTo(1);
    }

    @Test
    void globalSaturationOnABrandNewTraceLeavesNoGhostEntry() {
        // given
        SpanPermitTracker permitTracker = new SpanPermitTracker(1);
        AtomicLong clock = new AtomicLong();
        WatchdogTraceIndex index = index(100, 100, permitTracker, clock);
        index.admit("t1", "s1", WatchdogTestSpans.span(1, 1), clock.get(), () -> true);

        // when
        index.admit("t2", "s1", WatchdogTestSpans.span(2, 1), clock.get(), () -> true);

        // then
        assertThat(index.rejectedGlobalTotal()).isEqualTo(1);
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    void globalSaturationOnAnExistingTraceKeepsItsPriorStateIntact() {
        // given
        SpanPermitTracker permitTracker = new SpanPermitTracker(1);
        AtomicLong clock = new AtomicLong();
        WatchdogTraceIndex index = index(100, 5, permitTracker, clock);
        index.admit("t1", "s1", WatchdogTestSpans.span(1, 1), clock.get(), () -> true);

        // when
        index.admit("t1", "s2", WatchdogTestSpans.span(1, 2), clock.get(), () -> true);

        // then
        assertThat(index.rejectedGlobalTotal()).isEqualTo(1);
        assertThat(index.size()).isEqualTo(1);
        assertThat(index.trackedSpanCount()).isEqualTo(1);
    }

    @Test
    void dualSaturationIncrementsOnlyThePerTraceCounterNotTheGlobalOne() {
        // given
        SpanPermitTracker permitTracker = new SpanPermitTracker(1);
        AtomicLong clock = new AtomicLong();
        WatchdogTraceIndex index = index(100, 1, permitTracker, clock);
        index.admit("t1", "s1", WatchdogTestSpans.span(1, 1), clock.get(), () -> true);

        // when
        index.admit("t1", "s2", WatchdogTestSpans.span(1, 2), clock.get(), () -> true);

        // then
        assertThat(index.rejectedPerTraceTotal()).isEqualTo(1);
        assertThat(index.rejectedGlobalTotal()).isZero();
    }

    @Test
    void permitReleasedOnOnEndIsReusableByANewAdmission() {
        // given
        SpanPermitTracker permitTracker = new SpanPermitTracker(1);
        AtomicLong clock = new AtomicLong();
        WatchdogTraceIndex index = index(100, 100, permitTracker, clock);
        ReadWriteSpan first = WatchdogTestSpans.span(1, 1);
        index.admit("t1", "s1", first, clock.get(), () -> true);

        // when
        index.onEnd("t1", "s1", first);
        index.admit("t2", "s1", WatchdogTestSpans.span(2, 1), clock.get(), () -> true);

        // then: the released permit is reused, otherwise trackedSpanCount would stay at 0
        assertThat(index.trackedSpanCount()).isEqualTo(1);
        assertThat(index.rejectedGlobalTotal()).isZero();
    }

    @Test
    void admissionRemainsFailOpenWhenAdmissionOpenSupplierReportsClosed() {
        // given
        SpanPermitTracker permitTracker = new SpanPermitTracker(100);
        AtomicLong clock = new AtomicLong();
        WatchdogTraceIndex index = index(100, 100, permitTracker, clock);

        // when
        index.admit("t1", "s1", WatchdogTestSpans.span(1, 1), clock.get(), () -> false);

        // then
        assertThat(index.size()).isZero();
        assertThat(index.trackedSpanCount()).isZero();
        assertThat(index.rejectedPerTraceTotal()).isZero();
        assertThat(index.rejectedGlobalTotal()).isZero();
    }

    @Test
    void collectExpiredPrefersSpanReasonOverTraceReasonWhenBothExpireTogether() {
        // given
        AtomicLong clock = new AtomicLong();
        SpanPermitTracker permitTracker = new SpanPermitTracker(10);
        WatchdogTraceIndex index = new WatchdogTraceIndex(
                Duration.ofMillis(10).toNanos(), Duration.ofMillis(10).toNanos(),
                10, 10,
                clock::get, permitTracker
        );
        index.admit("t1", "s1", WatchdogTestSpans.span(1, 1), clock.get(), () -> true);
        clock.set(Duration.ofMillis(10).toNanos() + 1);

        // when
        List<WatchdogTraceIndex.ExpiredSpan> expired = index.collectExpired(clock.get());

        // then
        assertThat(expired).hasSize(1);
        assertThat(expired.getFirst().reason()).isEqualTo(WatchdogTraceIndex.TimeoutReason.SPAN);
    }

    @Test
    void collectExpiredUsesTraceReasonWhenOnlyTraceTimeoutIsExceeded() {
        // given
        AtomicLong clock = new AtomicLong();
        SpanPermitTracker permitTracker = new SpanPermitTracker(10);
        WatchdogTraceIndex index = new WatchdogTraceIndex(
                Duration.ofHours(1).toNanos(), Duration.ofMillis(10).toNanos(),
                10, 10,
                clock::get, permitTracker
        );
        index.admit("t1", "s1", WatchdogTestSpans.span(1, 1), clock.get(), () -> true);
        clock.set(Duration.ofMillis(10).toNanos() + 1);

        // when
        List<WatchdogTraceIndex.ExpiredSpan> expired = index.collectExpired(clock.get());

        // then
        assertThat(expired).hasSize(1);
        assertThat(expired.getFirst().reason()).isEqualTo(WatchdogTraceIndex.TimeoutReason.TRACE);
    }

    @Test
    void tombstoneSurvivesScanUntilItsOwnTraceEpochExpires() {
        // given
        AtomicLong clock = new AtomicLong();
        SpanPermitTracker permitTracker = new SpanPermitTracker(10);
        long spanTimeout = Duration.ofMillis(10).toNanos();
        long traceTimeout = Duration.ofMillis(100).toNanos();
        WatchdogTraceIndex index = new WatchdogTraceIndex(spanTimeout, traceTimeout, 10, 10, clock::get, permitTracker);
        index.admit("t1", "s1", WatchdogTestSpans.span(1, 1), clock.get(), () -> true);
        clock.set(spanTimeout + 1);

        // when
        List<WatchdogTraceIndex.ExpiredSpan> firstScan = index.collectExpired(clock.get());

        // then
        assertThat(firstScan).hasSize(1);
        assertThat(index.size()).isEqualTo(1);
        assertThat(index.nonEmptyTraceCount()).isZero();

        // when
        clock.set(traceTimeout + 1);
        List<WatchdogTraceIndex.ExpiredSpan> secondScan = index.collectExpired(clock.get());

        // then
        assertThat(secondScan).isEmpty();
        assertThat(index.size()).isZero();
    }

    @Test
    void hasEndedSpanIsPurgedByScanWithoutForceCloseOrLostPermit() {
        // given
        AtomicLong clock = new AtomicLong();
        SpanPermitTracker permitTracker = new SpanPermitTracker(10);
        WatchdogTraceIndex index = index(10, 10, permitTracker, clock);
        ReadWriteSpan span = WatchdogTestSpans.span(1, 1);
        index.admit("t1", "s1", span, clock.get(), () -> true);
        assertThat(index.trackedSpanCount()).isEqualTo(1);

        when(span.hasEnded()).thenReturn(true);

        // when
        List<WatchdogTraceIndex.ExpiredSpan> expired = index.collectExpired(clock.get());

        // then
        assertThat(expired).isEmpty();
        assertThat(index.trackedSpanCount()).isZero();
        assertThat(index.nonEmptyTraceCount()).isZero();
    }

    @Test
    @SuppressWarnings("resource")
    void concurrentScanAndOnEndNeverDoubleReleaseTheSamePermit() throws Exception {
        // given
        AtomicLong clock = new AtomicLong();
        SpanPermitTracker permitTracker = new SpanPermitTracker(10);
        WatchdogTraceIndex index = new WatchdogTraceIndex(
                Duration.ofNanos(1).toNanos(), Duration.ofHours(1).toNanos(),
                10, 10,
                clock::get, permitTracker
        );
        ReadWriteSpan span = WatchdogTestSpans.span(1, 1);
        CountDownLatch scannerEnteredHasEnded = new CountDownLatch(1);
        CountDownLatch releaseScanner = new CountDownLatch(1);
        when(span.hasEnded()).thenAnswer(invocation -> {
            scannerEnteredHasEnded.countDown();
            assertThat(releaseScanner.await(5, TimeUnit.SECONDS)).isTrue();
            return false;
        });
        index.admit("t1", "s1", span, clock.get(), () -> true);
        clock.set(2L);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // when
        try {
            Future<?> scan = executor.submit(() -> index.collectExpired(clock.get()));
            assertThat(scannerEnteredHasEnded.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> end = executor.submit(() -> index.onEnd("t1", "s1", span));
            releaseScanner.countDown();
            scan.get(5, TimeUnit.SECONDS);
            end.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        // then
        assertThat(index.trackedSpanCount()).isZero();
        assertThat(index.permitInvariantViolationTotal()).isZero();
    }

    @Test
    void evictOldestIfNeededEvictsTheComputedBatchAndReleasesTheirPermits() {
        // given
        SpanPermitTracker permitTracker = new SpanPermitTracker(100);
        AtomicLong clock = new AtomicLong();
        WatchdogTraceIndex index = index(3, 10, permitTracker, clock);
        for (int traceOrdinal = 1; traceOrdinal <= 5; traceOrdinal++) {
            clock.set(traceOrdinal);
            index.admit("t" + traceOrdinal, "s1", WatchdogTestSpans.span(traceOrdinal, 1), clock.get(), () -> true);
        }
        assertThat(index.trackedSpanCount()).isEqualTo(5);

        // when
        index.evictOldestIfNeeded();

        // then
        assertThat(index.size()).isEqualTo(3);
        assertThat(index.trackingEvictedTotal()).isEqualTo(2);
        assertThat(index.trackedSpanCount()).isEqualTo(3);

        index.admit("new-trace", "s1", WatchdogTestSpans.span(99, 1), clock.get(), () -> true);
        assertThat(index.trackedSpanCount()).isEqualTo(4);
    }

    @Test
    void evictionPrefersEmptyTracesOverLiveOnesEvenWhenTheyAreOlder() {
        // given
        SpanPermitTracker permitTracker = new SpanPermitTracker(100);
        AtomicLong clock = new AtomicLong();
        WatchdogTraceIndex index = index(3, 10, permitTracker, clock);

        ReadWriteSpan liveSpan = WatchdogTestSpans.span(1, 1);
        clock.set(1);
        index.admit("t-live", "s1", liveSpan, clock.get(), () -> true);

        ReadWriteSpan emptyFirst = WatchdogTestSpans.span(2, 1);
        clock.set(2);
        index.admit("t-empty-1", "s1", emptyFirst, clock.get(), () -> true);
        index.onEnd("t-empty-1", "s1", emptyFirst);

        ReadWriteSpan emptySecond = WatchdogTestSpans.span(3, 1);
        clock.set(3);
        index.admit("t-empty-2", "s1", emptySecond, clock.get(), () -> true);
        index.onEnd("t-empty-2", "s1", emptySecond);
        clock.set(4);
        index.admit("t-new-1", "s1", WatchdogTestSpans.span(4, 1), clock.get(), () -> true);
        clock.set(5);
        index.admit("t-new-2", "s1", WatchdogTestSpans.span(5, 1), clock.get(), () -> true);
        assertThat(index.size()).isEqualTo(5);

        index.evictOldestIfNeeded();

        // then
        assertThat(index.size()).isEqualTo(3);
        assertThat(index.trackingEvictedTotal()).isEqualTo(2);
        assertThat(index.trackedSpanCount()).isEqualTo(3);
    }

    @Test
    void evictionOfLiveTracesNeverExceedsTheActualOverflowEvenWhenTheAmortizedBatchFloorIsLarger() {
        // given
        SpanPermitTracker permitTracker = new SpanPermitTracker(100);
        AtomicLong clock = new AtomicLong();
        int capacity = 20;
        WatchdogTraceIndex index = index(capacity, 10, permitTracker, clock);
        for (int traceIndex = 1; traceIndex <= (capacity + 1); traceIndex++) {
            clock.set(traceIndex);
            index.admit("t-live-" + traceIndex, "s1", WatchdogTestSpans.span(traceIndex, 1), clock.get(), () -> true);
        }
        assertThat(index.size()).isEqualTo(capacity + 1);
        assertThat(index.trackedSpanCount()).isEqualTo(capacity + 1);

        // when
        index.evictOldestIfNeeded();

        // then
        assertThat(index.size()).isEqualTo(capacity);
        assertThat(index.trackingEvictedTotal()).isEqualTo(1);
        assertThat(index.trackedSpanCount()).isEqualTo(capacity);
    }

    @Test
    void admissionStaysFailOpenAboveTraceCapacityUntilEvictionActuallyRuns() {
        // given
        SpanPermitTracker permitTracker = new SpanPermitTracker(100);
        AtomicLong clock = new AtomicLong();
        WatchdogTraceIndex index = index(1, 10, permitTracker, clock);

        // when
        index.admit("t1", "s1", WatchdogTestSpans.span(1, 1), clock.get(), () -> true);
        index.admit("t2", "s1", WatchdogTestSpans.span(2, 1), clock.get(), () -> true);

        // then
        assertThat(index.size()).isEqualTo(2);
        assertThat(index.trackedSpanCount()).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("resource")
    void identitySafeEvictionNeverCorruptsThePermitTrackerUnderConcurrentTraceChurn() throws Exception {
        // given
        SpanPermitTracker permitTracker = new SpanPermitTracker(10_000);
        AtomicLong clock = new AtomicLong();
        WatchdogTraceIndex index = index(2, 100, permitTracker, clock);
        int churnThreads = 4;
        int iterationsPerThread = 2_000;
        String[] sharedTraceIds = {
                WatchdogTestSpans.hex(1, 32), WatchdogTestSpans.hex(2, 32), WatchdogTestSpans.hex(3, 32),
                WatchdogTestSpans.hex(4, 32), WatchdogTestSpans.hex(5, 32), WatchdogTestSpans.hex(6, 32),
        };
        AtomicBoolean evictorRunning = new AtomicBoolean(true);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(churnThreads + 1);

        // when
        try {
            Future<?> evictor = executor.submit(() -> {
                try {
                    start.await();
                    while (evictorRunning.get()) {
                        index.evictOldestIfNeeded();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });

            List<Future<?>> churners = new ArrayList<>();
            for (int threadIndex = 0; threadIndex < churnThreads; threadIndex++) {
                int capturedThreadIndex = threadIndex;
                churners.add(executor.submit(() -> {
                    try {
                        start.await();
                        for (int iteration = 0; iteration < iterationsPerThread; iteration++) {
                            String traceId = sharedTraceIds[(capturedThreadIndex + iteration) % sharedTraceIds.length];
                            String spanId = WatchdogTestSpans.hex(capturedThreadIndex * iterationsPerThread + iteration + 1, 16);
                            ReadWriteSpan span = WatchdogTestSpans.span(traceId, spanId);
                            index.admit(traceId, spanId, span, clock.incrementAndGet(), () -> true);
                            index.onEnd(traceId, spanId, span);
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }

            start.countDown();
            for (Future<?> churner : churners) {
                churner.get(30, TimeUnit.SECONDS);
            }
            evictorRunning.set(false);
            evictor.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        // then
        assertThat(index.permitInvariantViolationTotal()).isZero();
    }

    @Test
    void evictionSkipsAnEmptyCandidateRevivedBetweenPlanningAndCommit() {
        // given
        SpanPermitTracker permitTracker = new SpanPermitTracker(100);
        AtomicLong clock = new AtomicLong();
        WatchdogTraceIndex[] holder = new WatchdogTraceIndex[1];
        ReadWriteSpan revivedSpan = WatchdogTestSpans.span(1, 2);
        WatchdogTraceIndex index = index(20, permitTracker, clock,
                () -> holder[0].admit("t-empty-a", "s-revived", revivedSpan, clock.get(), () -> true)
        );
        holder[0] = index;
        clock.set(1);
        ReadWriteSpan firstEmpty = WatchdogTestSpans.span(1, 1);
        index.admit("t-empty-a", "s1", firstEmpty, clock.get(), () -> true);
        index.onEnd("t-empty-a", "s1", firstEmpty);
        clock.set(2);
        ReadWriteSpan secondEmpty = WatchdogTestSpans.span(2, 1);
        index.admit("t-empty-b", "s1", secondEmpty, clock.get(), () -> true);
        index.onEnd("t-empty-b", "s1", secondEmpty);
        for (int liveOrdinal = 1; liveOrdinal <= 19; liveOrdinal++) {
            clock.set(2 + liveOrdinal);
            index.admit("t-live-" + liveOrdinal, "s1",
                    WatchdogTestSpans.span(10 + liveOrdinal, 1), clock.get(), () -> true);
        }
        assertThat(index.size()).isEqualTo(21);

        // when
        index.evictOldestIfNeeded();

        // then
        assertThat(index.size()).isEqualTo(20);
        assertThat(index.trackingEvictedTotal()).isEqualTo(1);
        assertThat(index.liveTracesEvictedTotal()).isZero();
        assertThat(index.trackedSpanCount()).isEqualTo(20);
        assertThat(index.nonEmptyTraceCount()).isEqualTo(20);
    }

    @Test
    void boundedReplanCoversResidualOverflowWithoutInflatingLiveEvictionToTheHysteresisFloor() {
        // given
        SpanPermitTracker permitTracker = new SpanPermitTracker(1_000);
        AtomicLong clock = new AtomicLong();
        WatchdogTraceIndex[] holder = new WatchdogTraceIndex[1];
        ReadWriteSpan revivedSpan = WatchdogTestSpans.span(1, 2);
        WatchdogTraceIndex index = index(100, permitTracker, clock,
                () -> holder[0].admit("t-empty-a", "s-revived", revivedSpan, clock.get(), () -> true));
        holder[0] = index;
        clock.set(1);
        ReadWriteSpan firstEmpty = WatchdogTestSpans.span(1, 1);
        index.admit("t-empty-a", "s1", firstEmpty, clock.get(), () -> true);
        index.onEnd("t-empty-a", "s1", firstEmpty);
        clock.set(2);
        ReadWriteSpan secondEmpty = WatchdogTestSpans.span(2, 1);
        index.admit("t-empty-b", "s1", secondEmpty, clock.get(), () -> true);
        index.onEnd("t-empty-b", "s1", secondEmpty);
        for (int liveOrdinal = 1; liveOrdinal <= 101; liveOrdinal++) {
            clock.set(2 + liveOrdinal);
            index.admit("t-live-" + liveOrdinal, "s1",
                    WatchdogTestSpans.span(10 + liveOrdinal, 1), clock.get(), () -> true);
        }
        assertThat(index.size()).isEqualTo(103);
        assertThat(index.trackedSpanCount()).isEqualTo(101);

        // when
        index.evictOldestIfNeeded();

        // then
        assertThat(index.size()).isEqualTo(100);
        assertThat(index.trackingEvictedTotal()).isEqualTo(3);
        assertThat(index.liveTracesEvictedTotal()).isEqualTo(2);
        assertThat(index.trackedSpanCount()).isEqualTo(100);
    }

    @Test
    void evictionSkipsAStaleCandidateWhoseStateWasReplacedBetweenPlanningAndCommit() {
        // given
        SpanPermitTracker permitTracker = new SpanPermitTracker(100);
        AtomicLong clock = new AtomicLong();
        WatchdogTraceIndex[] holder = new WatchdogTraceIndex[1];
        ReadWriteSpan replacementSpan = WatchdogTestSpans.span(1, 2);
        WatchdogTraceIndex index = index(1_000L, 100L, 20, permitTracker, clock, () -> {
            clock.set(101);
            holder[0].collectExpired(101);
            holder[0].admit("t-stale-a", "s-replacement", replacementSpan, 101, () -> true);
        });
        holder[0] = index;
        ReadWriteSpan staleSpan = WatchdogTestSpans.span(1, 1);
        index.admit("t-stale-a", "s1", staleSpan, 0, () -> true);
        index.onEnd("t-stale-a", "s1", staleSpan);
        ReadWriteSpan spareSpan = WatchdogTestSpans.span(2, 1);
        index.admit("t-empty-b", "s1", spareSpan, 1, () -> true);
        index.onEnd("t-empty-b", "s1", spareSpan);
        for (int liveOrdinal = 1; liveOrdinal <= 19; liveOrdinal++) {
            index.admit("t-live-" + liveOrdinal, "s1", WatchdogTestSpans.span(10 + liveOrdinal, 1), 1, () -> true);
        }
        assertThat(index.size()).isEqualTo(21);

        // when
        index.evictOldestIfNeeded();

        // then
        assertThat(index.size()).isEqualTo(20);
        assertThat(index.trackingEvictedTotal()).isEqualTo(1);
        assertThat(index.liveTracesEvictedTotal()).isZero();
        assertThat(index.trackedSpanCount()).isEqualTo(20);
        assertThat(index.nonEmptyTraceCount()).isEqualTo(20);
    }

    @Test
    void evictingATombstoneStartsANewTraceEpochForTheSameTraceId() {
        // given
        SpanPermitTracker permitTracker = new SpanPermitTracker(100);
        AtomicLong clock = new AtomicLong();
        WatchdogTraceIndex index = index(1_000L, 100L, 1, permitTracker, clock, () -> {
        });

        ReadWriteSpan first = WatchdogTestSpans.span(1, 1);
        index.admit("t1", "s1", first, 0, () -> true);
        index.onEnd("t1", "s1", first);

        ReadWriteSpan other = WatchdogTestSpans.span(2, 1);
        index.admit("t2", "s1", other, 1, () -> true);
        index.evictOldestIfNeeded();

        assertThat(index.trackingEvictedTotal() - index.liveTracesEvictedTotal()).isEqualTo(1);
        index.onEnd("t2", "s1", other);

        // when
        ReadWriteSpan resumed = WatchdogTestSpans.span(1, 2);
        index.admit("t1", "s-resumed", resumed, 50, () -> true);

        // then
        assertThat(index.collectExpired(150)).isEmpty();
        assertThat(index.trackedSpanCount()).isEqualTo(1);

        List<WatchdogTraceIndex.ExpiredSpan> expired = index.collectExpired(151);
        assertThat(expired).hasSize(1);
        assertThat(expired.getFirst().traceId()).isEqualTo("t1");
        assertThat(expired.getFirst().reason()).isEqualTo(WatchdogTraceIndex.TimeoutReason.TRACE);
    }

    @Test
    void tombstoneEvictionLogDoesNotSuppressTheLiveEvictionLogInTheSameRateLimitWindow() {
        // given
        SpanPermitTracker permitTracker = new SpanPermitTracker(100);
        AtomicLong clock = new AtomicLong();
        WatchdogTraceIndex index = index(1, 10, permitTracker, clock);
        AgentLogCapture logs = new AgentLogCapture();
        logs.install();
        try {
            ReadWriteSpan tombstoned = WatchdogTestSpans.span(1, 1);
            index.admit("t1", "s1", tombstoned, 0, () -> true);
            index.onEnd("t1", "s1", tombstoned);
            index.admit("t2", "s1", WatchdogTestSpans.span(2, 1), 0, () -> true);

            // when
            index.evictOldestIfNeeded();
            index.admit("t3", "s1", WatchdogTestSpans.span(3, 1), 0, () -> true);
            index.evictOldestIfNeeded();

            // then
            assertThat(logs.events()).extracting(AgentLogEvent::eventCode)
                    .containsExactly(AgentEventCodes.EXPORT_WATCHDOG,
                            AgentEventCodes.EXPORT_WATCHDOG_LIVE_EVICTION);
            assertThat(index.liveTracesEvictedTotal()).isEqualTo(1);
        } finally {
            logs.restore();
        }
    }
}
