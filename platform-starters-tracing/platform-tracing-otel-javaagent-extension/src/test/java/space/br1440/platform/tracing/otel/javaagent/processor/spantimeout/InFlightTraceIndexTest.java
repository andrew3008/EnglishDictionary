package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;

import io.opentelemetry.sdk.trace.ReadWriteSpan;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class InFlightTraceIndexTest {

    private static final long HOUR_NANOS = Duration.ofHours(1).toNanos();

    private static InFlightTraceIndex index(int maxTrackedTraces,
                                            int maxTrackedSpansPerTrace,
                                            int maxTrackedSpans,
                                            AtomicLong clock) {
        return new InFlightTraceIndex(
                HOUR_NANOS, HOUR_NANOS,
                maxTrackedTraces, maxTrackedSpansPerTrace, maxTrackedSpans,
                clock::get
        );
    }

    @SuppressWarnings("SameParameterValue")
    private static InFlightTraceIndex index(int maxTrackedTraces,
                                            AtomicLong clock,
                                            Runnable afterFirstEvictionPlan) {
        return new InFlightTraceIndex(
                HOUR_NANOS, HOUR_NANOS,
                maxTrackedTraces, 10, 1_000,
                clock::get,
                afterFirstEvictionPlan
        );
    }

    @SuppressWarnings("SameParameterValue")
    private static InFlightTraceIndex index(long spanTimeoutNanos,
                                            long traceTimeoutNanos,
                                            int maxTrackedTraces,
                                            AtomicLong clock,
                                            Runnable afterFirstEvictionPlan) {
        return new InFlightTraceIndex(
                spanTimeoutNanos, traceTimeoutNanos,
                maxTrackedTraces, 10, 1_000,
                clock::get,
                afterFirstEvictionPlan
        );
    }

    private static List<ExpiredSpan> collectExpired(InFlightTraceIndex index, long now) {
        Deque<ExpiredSpan> into = new ArrayDeque<>();
        index.collectExpired(now, into);
        return List.copyOf(into);
    }

    private static void admitEnded(InFlightTraceIndex index, String traceId, ReadWriteSpan span, long now) {
        index.admit(traceId, span.getSpanContext().getSpanId(), span, now, () -> true);
        index.onEnd(traceId, span.getSpanContext().getSpanId(), span);
    }

    @Test
    void duplicateSpanIdIsNoOpAndDoesNotConsumeAnExtraPermit() {
        // given
        AtomicLong clock = new AtomicLong();
        InFlightTraceIndex index = index(5, 5, 5, clock);
        ReadWriteSpan span = SpanTimeoutTestSpans.span(1, 1);

        // when
        index.admit("t1", "s1", span, clock.get(), () -> true);
        index.admit("t1", "s1", span, clock.get(), () -> true);

        // then
        assertThat(index.trackedSpanCount()).isEqualTo(1);
        assertThat(index.acquiredTraceSlotCount()).isEqualTo(1);
        assertThat(index.rejectedPerTraceTotal()).isZero();
        assertThat(index.rejectedGlobalTotal()).isZero();
    }

    @Test
    void perTraceSaturationRejectsSecondDistinctSpanOnSameTrace() {
        // given
        AtomicLong clock = new AtomicLong();
        InFlightTraceIndex index = index(100, 1, 100, clock);

        // when
        index.admit("t1", "s1", SpanTimeoutTestSpans.span(1, 1), clock.get(), () -> true);
        index.admit("t1", "s2", SpanTimeoutTestSpans.span(1, 2), clock.get(), () -> true);

        // then
        assertThat(index.rejectedPerTraceTotal()).isEqualTo(1);
        assertThat(index.rejectedGlobalTotal()).isZero();
        assertThat(index.trackedSpanCount()).isEqualTo(1);
    }

    @Test
    void globalSaturationOnABrandNewTraceRollsBackTheTraceSlotAndLeavesNoGhostEntry() {
        // given
        AtomicLong clock = new AtomicLong();
        InFlightTraceIndex index = index(100, 100, 1, clock);
        index.admit("t1", "s1", SpanTimeoutTestSpans.span(1, 1), clock.get(), () -> true);

        // when
        index.admit("t2", "s1", SpanTimeoutTestSpans.span(2, 1), clock.get(), () -> true);

        // then
        assertThat(index.rejectedGlobalTotal()).isEqualTo(1);
        assertThat(index.rejectedTraceCapacityTotal()).isZero();
        assertThat(index.size()).isEqualTo(1);
        assertThat(index.acquiredTraceSlotCount()).isEqualTo(1);
        assertThat(index.traceSlotInvariantViolationTotal()).isZero();
    }

    @Test
    void globalSaturationOnAnExistingTraceKeepsItsPriorStateIntact() {
        // given
        AtomicLong clock = new AtomicLong();
        InFlightTraceIndex index = index(100, 5, 1, clock);
        index.admit("t1", "s1", SpanTimeoutTestSpans.span(1, 1), clock.get(), () -> true);

        // when
        index.admit("t1", "s2", SpanTimeoutTestSpans.span(1, 2), clock.get(), () -> true);

        // then
        assertThat(index.rejectedGlobalTotal()).isEqualTo(1);
        assertThat(index.size()).isEqualTo(1);
        assertThat(index.trackedSpanCount()).isEqualTo(1);
    }

    @Test
    void dualSaturationIncrementsOnlyThePerTraceCounterNotTheGlobalOne() {
        // given
        AtomicLong clock = new AtomicLong();
        InFlightTraceIndex index = index(100, 1, 1, clock);
        index.admit("t1", "s1", SpanTimeoutTestSpans.span(1, 1), clock.get(), () -> true);

        // when
        index.admit("t1", "s2", SpanTimeoutTestSpans.span(1, 2), clock.get(), () -> true);

        // then
        assertThat(index.rejectedPerTraceTotal()).isEqualTo(1);
        assertThat(index.rejectedGlobalTotal()).isZero();
    }

    @Test
    void permitReleasedOnOnEndIsReusableByANewAdmission() {
        // given
        AtomicLong clock = new AtomicLong();
        InFlightTraceIndex index = index(100, 100, 1, clock);
        ReadWriteSpan first = SpanTimeoutTestSpans.span(1, 1);
        index.admit("t1", "s1", first, clock.get(), () -> true);

        // when
        index.onEnd("t1", "s1", first);
        index.admit("t2", "s1", SpanTimeoutTestSpans.span(2, 1), clock.get(), () -> true);

        // then
        assertThat(index.trackedSpanCount()).isEqualTo(1);
        assertThat(index.rejectedGlobalTotal()).isZero();
    }

    @Test
    void admissionIsSkippedWhenAdmissionOpenSupplierReportsClosed() {
        // given
        AtomicLong clock = new AtomicLong();
        InFlightTraceIndex index = index(100, 100, 100, clock);

        // when
        index.admit("t1", "s1", SpanTimeoutTestSpans.span(1, 1), clock.get(), () -> false);

        // then
        assertThat(index.size()).isZero();
        assertThat(index.trackedSpanCount()).isZero();
        assertThat(index.acquiredTraceSlotCount()).isZero();
        assertThat(index.rejectedPerTraceTotal()).isZero();
        assertThat(index.rejectedGlobalTotal()).isZero();
        assertThat(index.rejectedTraceCapacityTotal()).isZero();
    }

    @Test
    void traceCapacityRejectsABrandNewTraceWithoutTouchingSpanPermits() {
        // given
        AtomicLong clock = new AtomicLong();
        InFlightTraceIndex index = index(1, 10, 100, clock);
        index.admit("t1", "s1", SpanTimeoutTestSpans.span(1, 1), clock.get(), () -> true);

        // when
        index.admit("t2", "s1", SpanTimeoutTestSpans.span(2, 1), clock.get(), () -> true);

        // then
        assertThat(index.rejectedTraceCapacityTotal()).isEqualTo(1);
        assertThat(index.rejectedGlobalTotal()).isZero();
        assertThat(index.size()).isEqualTo(1);
        assertThat(index.trackedSpanCount()).isEqualTo(1);
        assertThat(index.acquiredTraceSlotCount()).isEqualTo(1);
    }

    @Test
    void traceCapacityStillAdmitsSpansOfAnAlreadyTrackedTrace() {
        // given
        AtomicLong clock = new AtomicLong();
        InFlightTraceIndex index = index(1, 10, 100, clock);
        index.admit("t1", "s1", SpanTimeoutTestSpans.span(1, 1), clock.get(), () -> true);

        // when
        index.admit("t1", "s2", SpanTimeoutTestSpans.span(1, 2), clock.get(), () -> true);

        // then
        assertThat(index.rejectedTraceCapacityTotal()).isZero();
        assertThat(index.trackedSpanCount()).isEqualTo(2);
    }

    @Test
    void collectExpiredPrefersSpanReasonOverTraceReasonWhenBothExpireTogether() {
        // given
        AtomicLong clock = new AtomicLong();
        InFlightTraceIndex index = new InFlightTraceIndex(
                Duration.ofMillis(10).toNanos(), Duration.ofMillis(10).toNanos(),
                10, 10, 10,
                clock::get
        );
        index.admit("t1", "s1", SpanTimeoutTestSpans.span(1, 1), clock.get(), () -> true);
        clock.set(Duration.ofMillis(10).toNanos() + 1);

        // when
        List<ExpiredSpan> expired = collectExpired(index, clock.get());

        // then
        assertThat(expired).hasSize(1);
        assertThat(expired.getFirst().reason()).isEqualTo(TimeoutReason.SPAN);
        assertThat(expired.getFirst().spanId()).isEqualTo("s1");
    }

    @Test
    void collectExpiredUsesTraceReasonWhenOnlyTraceTimeoutIsExceeded() {
        // given
        AtomicLong clock = new AtomicLong();
        InFlightTraceIndex index = new InFlightTraceIndex(
                Duration.ofHours(1).toNanos(), Duration.ofMillis(10).toNanos(),
                10, 10, 10,
                clock::get
        );
        index.admit("t1", "s1", SpanTimeoutTestSpans.span(1, 1), clock.get(), () -> true);
        clock.set(Duration.ofMillis(10).toNanos() + 1);

        // when
        List<ExpiredSpan> expired = collectExpired(index, clock.get());

        // then
        assertThat(expired).hasSize(1);
        assertThat(expired.getFirst().reason()).isEqualTo(TimeoutReason.TRACE);
    }

    @Test
    void extractedCandidateKeepsItsPermitUntilTheCallerReleasesIt() {
        // given
        AtomicLong clock = new AtomicLong();
        InFlightTraceIndex index = new InFlightTraceIndex(
                Duration.ofMillis(10).toNanos(), HOUR_NANOS,
                10, 10, 10,
                clock::get
        );
        index.admit("t1", "s1", SpanTimeoutTestSpans.span(1, 1), clock.get(), () -> true);
        clock.set(Duration.ofMillis(10).toNanos() + 1);

        // when
        List<ExpiredSpan> expired = collectExpired(index, clock.get());

        // then
        assertThat(expired).hasSize(1);
        assertThat(index.trackedSpanCount()).isEqualTo(1);
        assertThat(index.nonEmptyTraceCount()).isZero();

        index.releaseCandidatePermit(expired.getFirst(), "force-end");
        assertThat(index.trackedSpanCount()).isZero();
        assertThat(index.permitInvariantViolationTotal()).isZero();
    }

    @Test
    void emptyStateSurvivesScanUntilItsOwnTraceEpochExpires() {
        // given
        AtomicLong clock = new AtomicLong();
        long spanTimeout = Duration.ofMillis(10).toNanos();
        long traceTimeout = Duration.ofMillis(100).toNanos();
        InFlightTraceIndex index = new InFlightTraceIndex(spanTimeout, traceTimeout, 10, 10, 10, clock::get);
        index.admit("t1", "s1", SpanTimeoutTestSpans.span(1, 1), clock.get(), () -> true);
        clock.set(spanTimeout + 1);

        // when
        List<ExpiredSpan> firstScan = collectExpired(index, clock.get());
        index.releaseCandidatePermit(firstScan.getFirst(), "force-end");

        // then
        assertThat(firstScan).hasSize(1);
        assertThat(index.size()).isEqualTo(1);
        assertThat(index.acquiredTraceSlotCount()).isEqualTo(1);
        assertThat(index.nonEmptyTraceCount()).isZero();

        // when
        clock.set(traceTimeout + 1);
        List<ExpiredSpan> secondScan = collectExpired(index, clock.get());

        // then
        assertThat(secondScan).isEmpty();
        assertThat(index.size()).isZero();
        assertThat(index.acquiredTraceSlotCount()).isZero();
    }

    @Test
    void hasEndedSpanIsPurgedByScanWithoutForceCloseOrLostPermit() {
        // given
        AtomicLong clock = new AtomicLong();
        InFlightTraceIndex index = index(10, 10, 10, clock);
        ReadWriteSpan span = SpanTimeoutTestSpans.span(1, 1);
        index.admit("t1", "s1", span, clock.get(), () -> true);
        assertThat(index.trackedSpanCount()).isEqualTo(1);

        when(span.hasEnded()).thenReturn(true);

        // when
        List<ExpiredSpan> expired = collectExpired(index, clock.get());

        // then
        assertThat(expired).isEmpty();
        assertThat(index.trackedSpanCount()).isZero();
        assertThat(index.nonEmptyTraceCount()).isZero();
    }

    @Test
    void drainAllReleasesEverySpanPermitAndTraceSlot() {
        // given
        AtomicLong clock = new AtomicLong();
        InFlightTraceIndex index = index(10, 10, 10, clock);
        index.admit("t1", "s1", SpanTimeoutTestSpans.span(1, 1), clock.get(), () -> true);
        index.admit("t1", "s2", SpanTimeoutTestSpans.span(1, 2), clock.get(), () -> true);
        index.admit("t2", "s1", SpanTimeoutTestSpans.span(2, 1), clock.get(), () -> true);

        // when
        index.drainAll();

        // then
        assertThat(index.size()).isZero();
        assertThat(index.trackedSpanCount()).isZero();
        assertThat(index.acquiredTraceSlotCount()).isZero();
        assertThat(index.permitInvariantViolationTotal()).isZero();
        assertThat(index.traceSlotInvariantViolationTotal()).isZero();
    }

    @Test
    @SuppressWarnings("resource")
    void concurrentScanAndOnEndNeverDoubleReleaseTheSamePermit() throws Exception {
        // given
        AtomicLong clock = new AtomicLong();
        InFlightTraceIndex index = new InFlightTraceIndex(
                Duration.ofNanos(1).toNanos(), Duration.ofHours(1).toNanos(),
                10, 10, 10,
                clock::get
        );
        ReadWriteSpan span = SpanTimeoutTestSpans.span(1, 1);
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
        Deque<ExpiredSpan> batch = new ArrayDeque<>();

        // when
        try {
            Future<?> scan = executor.submit(() -> index.collectExpired(clock.get(), batch));
            assertThat(scannerEnteredHasEnded.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> end = executor.submit(() -> index.onEnd("t1", "s1", span));
            releaseScanner.countDown();
            scan.get(5, TimeUnit.SECONDS);
            end.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        for (ExpiredSpan candidate : batch) {
            index.releaseCandidatePermit(candidate, "force-end");
        }

        // then
        assertThat(index.trackedSpanCount()).isZero();
        assertThat(index.permitInvariantViolationTotal()).isZero();
    }

    @Test
    void liveTracesAreNeverEvictedEvenUnderTraceCapacityPressure() {
        // given
        AtomicLong clock = new AtomicLong();
        InFlightTraceIndex index = index(2, 10, 100, clock);
        clock.set(1);
        index.admit("t1", "s1", SpanTimeoutTestSpans.span(1, 1), clock.get(), () -> true);
        clock.set(2);
        index.admit("t2", "s1", SpanTimeoutTestSpans.span(2, 1), clock.get(), () -> true);
        index.admit("t3", "s1", SpanTimeoutTestSpans.span(3, 1), clock.get(), () -> true);
        assertThat(index.rejectedTraceCapacityTotal()).isEqualTo(1);

        // when
        index.relieveTracePressure();

        // then
        assertThat(index.size()).isEqualTo(2);
        assertThat(index.trackingEvictedTotal()).isZero();
        assertThat(index.trackedSpanCount()).isEqualTo(2);
        assertThat(index.nonEmptyTraceCount()).isEqualTo(2);
    }

    @Test
    void relieveTracePressureEvictsTheOldestEmptyStateAndFreesItsSlotForANewTrace() {
        // given
        AtomicLong clock = new AtomicLong();
        InFlightTraceIndex index = index(3, 10, 100, clock);
        clock.set(1);
        admitEnded(index, "t-empty-old", SpanTimeoutTestSpans.span(1, 1), clock.get());
        clock.set(2);
        admitEnded(index, "t-empty-new", SpanTimeoutTestSpans.span(2, 1), clock.get());
        clock.set(3);
        index.admit("t-live", "s1", SpanTimeoutTestSpans.span(3, 1), clock.get(), () -> true);
        index.admit("t-rejected", "s1", SpanTimeoutTestSpans.span(4, 1), clock.get(), () -> true);
        assertThat(index.rejectedTraceCapacityTotal()).isEqualTo(1);

        // when
        index.relieveTracePressure();

        // then
        assertThat(index.size()).isEqualTo(2);
        assertThat(index.trackingEvictedTotal()).isEqualTo(1);
        assertThat(index.acquiredTraceSlotCount()).isEqualTo(2);
        assertThat(index.trackedSpanCount()).isEqualTo(1);

        index.admit("t-empty-old", "s-resumed", SpanTimeoutTestSpans.span(1, 2), clock.get(), () -> true);
        assertThat(index.size()).isEqualTo(3);
        assertThat(index.rejectedTraceCapacityTotal()).isEqualTo(1);
    }

    @Test
    void relieveTracePressureIsANoOpWhileSlotsAreFreeAndNoRejectionWasObserved() {
        // given
        AtomicLong clock = new AtomicLong();
        InFlightTraceIndex index = index(10, 10, 100, clock);
        admitEnded(index, "t-empty", SpanTimeoutTestSpans.span(1, 1), clock.get());
        index.admit("t-live", "s1", SpanTimeoutTestSpans.span(2, 1), clock.get(), () -> true);

        // when
        index.relieveTracePressure();

        // then
        assertThat(index.size()).isEqualTo(2);
        assertThat(index.trackingEvictedTotal()).isZero();
    }

    @Test
    void pressureSignalIsConsumedByASingleReliefPass() {
        // given
        AtomicLong clock = new AtomicLong();
        InFlightTraceIndex index = index(2, 10, 100, clock);
        clock.set(1);
        admitEnded(index, "t-empty-a", SpanTimeoutTestSpans.span(1, 1), clock.get());
        clock.set(2);
        admitEnded(index, "t-empty-b", SpanTimeoutTestSpans.span(2, 1), clock.get());
        index.admit("t-rejected", "s1", SpanTimeoutTestSpans.span(3, 1), clock.get(), () -> true);

        // when
        index.relieveTracePressure();
        index.relieveTracePressure();

        // then
        assertThat(index.trackingEvictedTotal()).isEqualTo(1);
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    void evictionSkipsAnEmptyCandidateRevivedBetweenPlanningAndCommit() {
        // given
        AtomicLong clock = new AtomicLong();
        InFlightTraceIndex[] holder = new InFlightTraceIndex[1];
        ReadWriteSpan revivedSpan = SpanTimeoutTestSpans.span(1, 2);
        InFlightTraceIndex index = index(20, clock,
                () -> holder[0].admit("t-empty-a", "s-revived", revivedSpan, clock.get(), () -> true)
        );
        holder[0] = index;
        clock.set(1);
        admitEnded(index, "t-empty-a", SpanTimeoutTestSpans.span(1, 1), clock.get());
        clock.set(2);
        admitEnded(index, "t-empty-b", SpanTimeoutTestSpans.span(2, 1), clock.get());
        for (int liveOrdinal = 1; liveOrdinal <= 18; liveOrdinal++) {
            clock.set(2 + liveOrdinal);
            index.admit("t-live-" + liveOrdinal, "s1",
                    SpanTimeoutTestSpans.span(10 + liveOrdinal, 1), clock.get(), () -> true);
        }
        assertThat(index.size()).isEqualTo(20);

        // when
        index.relieveTracePressure();

        // then
        assertThat(index.size()).isEqualTo(19);
        assertThat(index.trackingEvictedTotal()).isEqualTo(1);
        assertThat(index.trackedSpanCount()).isEqualTo(19);
        assertThat(index.nonEmptyTraceCount()).isEqualTo(19);
        assertThat(index.acquiredTraceSlotCount()).isEqualTo(19);
    }

    @Test
    void evictionSkipsAStaleCandidateWhoseStateWasReplacedBetweenPlanningAndCommit() {
        // given
        AtomicLong clock = new AtomicLong();
        InFlightTraceIndex[] holder = new InFlightTraceIndex[1];
        ReadWriteSpan replacementSpan = SpanTimeoutTestSpans.span(1, 2);
        InFlightTraceIndex index = index(1_000L, 100L, 20, clock, () -> {
            clock.set(101);
            collectExpired(holder[0], 101);
            holder[0].admit("t-stale-a", "s-replacement", replacementSpan, 101, () -> true);
        });
        holder[0] = index;
        admitEnded(index, "t-stale-a", SpanTimeoutTestSpans.span(1, 1), 0);
        admitEnded(index, "t-empty-b", SpanTimeoutTestSpans.span(2, 1), 1);
        for (int liveOrdinal = 1; liveOrdinal <= 18; liveOrdinal++) {
            index.admit("t-live-" + liveOrdinal, "s1", SpanTimeoutTestSpans.span(10 + liveOrdinal, 1), 1, () -> true);
        }
        assertThat(index.size()).isEqualTo(20);

        // when
        index.relieveTracePressure();

        // then
        assertThat(index.size()).isEqualTo(19);
        assertThat(index.trackingEvictedTotal()).isEqualTo(1);
        assertThat(index.trackedSpanCount()).isEqualTo(19);
        assertThat(index.nonEmptyTraceCount()).isEqualTo(19);
        assertThat(index.acquiredTraceSlotCount()).isEqualTo(19);
        assertThat(index.traceSlotInvariantViolationTotal()).isZero();
    }

    @Test
    void evictingAnEmptyStateStartsANewTraceEpochForTheSameTraceId() {
        // given
        AtomicLong clock = new AtomicLong();
        InFlightTraceIndex index = index(1_000L, 100L, 2, clock, () -> {
        });
        admitEnded(index, "t1", SpanTimeoutTestSpans.span(1, 1), 0);
        ReadWriteSpan other = SpanTimeoutTestSpans.span(2, 1);
        index.admit("t2", "s1", other, 1, () -> true);
        index.admit("t3", "s1", SpanTimeoutTestSpans.span(3, 1), 1, () -> true);
        index.relieveTracePressure();
        assertThat(index.trackingEvictedTotal()).isEqualTo(1);
        index.onEnd("t2", "s1", other);

        // when
        ReadWriteSpan resumed = SpanTimeoutTestSpans.span(1, 2);
        index.admit("t1", "s-resumed", resumed, 50, () -> true);

        // then
        assertThat(collectExpired(index, 150)).isEmpty();
        assertThat(index.trackedSpanCount()).isEqualTo(1);

        List<ExpiredSpan> expired = collectExpired(index, 151);
        assertThat(expired).hasSize(1);
        assertThat(expired.getFirst().traceId()).isEqualTo("t1");
        assertThat(expired.getFirst().reason()).isEqualTo(TimeoutReason.TRACE);
    }

    @Test
    @SuppressWarnings("resource")
    void identitySafeEvictionNeverCorruptsEitherLedgerUnderConcurrentTraceChurn() throws Exception {
        // given
        AtomicLong clock = new AtomicLong();
        InFlightTraceIndex index = index(2, 100, 10_000, clock);
        int churnThreads = 4;
        int iterationsPerThread = 2_000;
        String[] sharedTraceIds = {
                SpanTimeoutTestSpans.hex(1, 32), SpanTimeoutTestSpans.hex(2, 32), SpanTimeoutTestSpans.hex(3, 32),
                SpanTimeoutTestSpans.hex(4, 32), SpanTimeoutTestSpans.hex(5, 32), SpanTimeoutTestSpans.hex(6, 32),
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
                        index.relieveTracePressure();
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
                            String spanId = SpanTimeoutTestSpans.hex(capturedThreadIndex * iterationsPerThread + iteration + 1, 16);
                            ReadWriteSpan span = SpanTimeoutTestSpans.span(traceId, spanId);
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
        assertThat(index.traceSlotInvariantViolationTotal()).isZero();
        assertThat(index.trackedSpanCount()).isZero();
        assertThat(index.acquiredTraceSlotCount()).isEqualTo(index.size());
        assertThat(index.size()).isLessThanOrEqualTo(2);
    }

    @Test
    @SuppressWarnings("resource")
    void concurrentAdmissionOfDistinctTracesNeverExceedsTraceCapacity() throws Exception {
        // given
        int maxTrackedTraces = 50;
        int threads = 8;
        int tracesPerThread = 500;
        AtomicLong clock = new AtomicLong();
        InFlightTraceIndex index = index(maxTrackedTraces, 100, 100_000, clock);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        // when
        try {
            List<Future<?>> admitters = new ArrayList<>();
            for (int threadIndex = 0; threadIndex < threads; threadIndex++) {
                int capturedThreadIndex = threadIndex;
                admitters.add(executor.submit(() -> {
                    start.await();
                    for (int ordinal = 0; ordinal < tracesPerThread; ordinal++) {
                        int traceOrdinal = capturedThreadIndex * tracesPerThread + ordinal + 1;
                        String traceId = SpanTimeoutTestSpans.hex(traceOrdinal, 32);
                        index.admit(traceId, "s1", SpanTimeoutTestSpans.span(traceOrdinal, 1),
                                clock.incrementAndGet(), () -> true);
                    }
                    return null;
                }));
            }

            start.countDown();
            for (Future<?> admitter : admitters) {
                admitter.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        // then
        assertThat(index.size()).isEqualTo(maxTrackedTraces);
        assertThat(index.acquiredTraceSlotCount()).isEqualTo(maxTrackedTraces);
        assertThat(index.trackedSpanCount()).isEqualTo(maxTrackedTraces);
        assertThat(index.rejectedTraceCapacityTotal()).isEqualTo((long) threads * tracesPerThread - maxTrackedTraces);
        assertThat(index.rejectedGlobalTotal()).isZero();
        assertThat(index.permitInvariantViolationTotal()).isZero();
        assertThat(index.traceSlotInvariantViolationTotal()).isZero();
    }

    @Test
    @SuppressWarnings("resource")
    void acquiredTraceSlotsNeverExceedTraceCapacityWhilePublishedStatesAreRemovedConcurrently() throws Exception {
        // given
        int maxTrackedTraces = 4;
        int churnThreads = 4;
        int iterationsPerThread = 3_000;
        AtomicLong clock = new AtomicLong();
        InFlightTraceIndex index = index(maxTrackedTraces, 100, 10_000, clock);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong maxObservedSlots = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(churnThreads + 2);

        // when
        try {
            // Снятие опубликованных состояний идёт на потоке relief. Наблюдатель фиксирует пик ledger'а
            // слотов — линеаризуемой границы; ConcurrentHashMap.size() — слабо согласованная оценка
            // и под конкурентным снятием/публикацией транзиентно может показать max + 1.
            Future<?> evictor = executor.submit(() -> {
                start.await();
                while (running.get()) {
                    index.relieveTracePressure();
                }
                return null;
            });
            Future<?> observer = executor.submit(() -> {
                start.await();
                while (running.get()) {
                    maxObservedSlots.accumulateAndGet(index.acquiredTraceSlotCount(), Math::max);
                }
                return null;
            });

            List<Future<?>> churners = new ArrayList<>();
            for (int threadIndex = 0; threadIndex < churnThreads; threadIndex++) {
                int capturedThreadIndex = threadIndex;
                churners.add(executor.submit(() -> {
                    start.await();
                    for (int iteration = 0; iteration < iterationsPerThread; iteration++) {
                        int traceOrdinal = capturedThreadIndex * iterationsPerThread + iteration + 1;
                        String traceId = SpanTimeoutTestSpans.hex(traceOrdinal, 32);
                        ReadWriteSpan span = SpanTimeoutTestSpans.span(traceOrdinal, 1);
                        index.admit(traceId, "s1", span, clock.incrementAndGet(), () -> true);
                        index.onEnd(traceId, "s1", span);
                    }
                    return null;
                }));
            }

            start.countDown();
            for (Future<?> churner : churners) {
                churner.get(30, TimeUnit.SECONDS);
            }
            running.set(false);
            evictor.get(10, TimeUnit.SECONDS);
            observer.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        // then
        assertThat(maxObservedSlots.get()).isLessThanOrEqualTo(maxTrackedTraces);
        assertThat(index.size()).isLessThanOrEqualTo(maxTrackedTraces);
        assertThat(index.acquiredTraceSlotCount()).isEqualTo(index.size());
        assertThat(index.trackedSpanCount()).isZero();
        assertThat(index.permitInvariantViolationTotal()).isZero();
        assertThat(index.traceSlotInvariantViolationTotal()).isZero();
    }

    @Test
    void traceCapacityRejectionAndReliefAreEachLoggedOnceWithinTheirRateLimitWindows() {
        // given
        AtomicLong clock = new AtomicLong();
        InFlightTraceIndex index = index(1, 10, 100, clock);
        try (AgentLogCapture logs = AgentLogCapture.installed()) {
            admitEnded(index, "t1", SpanTimeoutTestSpans.span(1, 1), 0);

            // when
            index.admit("t2", "s1", SpanTimeoutTestSpans.span(2, 1), 0, () -> true);
            index.admit("t3", "s1", SpanTimeoutTestSpans.span(3, 1), 0, () -> true);
            index.relieveTracePressure();

            // then
            assertThat(index.rejectedTraceCapacityTotal()).isEqualTo(2);
            assertThat(index.trackingEvictedTotal()).isEqualTo(1);
            assertThat(logs.events()).extracting(CapturedLogEvent::eventCode)
                    .containsExactly(AgentEventCode.EXPORT_SPAN_TIMEOUT, AgentEventCode.EXPORT_SPAN_TIMEOUT);
            assertThat(logs.events()).extracting(CapturedLogEvent::message)
                    .containsExactly(
                            "Span timeout rejected span tracking: tracked-trace capacity reached",
                            "Span timeout evicted empty trace states under pressure");
        }
    }
}
