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

import io.opentelemetry.sdk.trace.ReadWriteSpan;

/**
 * Прямая характеризация {@link WatchdogTraceIndex}: admission, scan/purge и eviction без
 * прохода через фасад или реальный OTel SDK. Минимальная матрица из ревью декомпозиции —
 * admission (duplicate/per-trace/global/dual saturation/ghost trace/permit round-trip), scan
 * (span/trace timeout, tombstone, hasEnded-purge) и eviction (batch formula, identity-safe
 * eviction, released permits, soft trace cap).
 */
class WatchdogTraceIndexTest {

    private static final long HOUR_NANOS = Duration.ofHours(1).toNanos();

    private static WatchdogTraceIndex index(int maxTrackedTraces, int maxTrackedSpansPerTrace,
                                             SpanPermitLedger ledger, AtomicLong clock) {
        return new WatchdogTraceIndex(HOUR_NANOS, HOUR_NANOS, maxTrackedTraces, maxTrackedSpansPerTrace,
                clock::get, ledger);
    }

    // ---------------------------------------------------------------- admission

    @Test
    void duplicateSpanIdIsNoOpAndDoesNotConsumeAnExtraPermit() {
        // given
        SpanPermitLedger ledger = new SpanPermitLedger(5);
        AtomicLong clock = new AtomicLong();
        WatchdogTraceIndex index = index(5, 5, ledger, clock);
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
        SpanPermitLedger ledger = new SpanPermitLedger(100);
        AtomicLong clock = new AtomicLong();
        WatchdogTraceIndex index = index(100, 1, ledger, clock);

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
        // given: ledger has exactly one permit, already consumed by trace "t1"
        SpanPermitLedger ledger = new SpanPermitLedger(1);
        AtomicLong clock = new AtomicLong();
        WatchdogTraceIndex index = index(100, 100, ledger, clock);
        index.admit("t1", "s1", WatchdogTestSpans.span(1, 1), clock.get(), () -> true);

        // when: a span on a never-seen trace is rejected by the global budget
        index.admit("t2", "s1", WatchdogTestSpans.span(2, 1), clock.get(), () -> true);

        // then: no ghost entry is left behind for the rejected new trace
        assertThat(index.rejectedGlobalTotal()).isEqualTo(1);
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    void globalSaturationOnAnExistingTraceKeepsItsPriorStateIntact() {
        // given: per-trace cap is generous, but the global ledger only ever grants one permit
        SpanPermitLedger ledger = new SpanPermitLedger(1);
        AtomicLong clock = new AtomicLong();
        WatchdogTraceIndex index = index(100, 5, ledger, clock);
        index.admit("t1", "s1", WatchdogTestSpans.span(1, 1), clock.get(), () -> true);

        // when: a second span on the SAME trace is rejected by the global budget
        index.admit("t1", "s2", WatchdogTestSpans.span(1, 2), clock.get(), () -> true);

        // then: trace "t1" is not dropped, its first span is still tracked
        assertThat(index.rejectedGlobalTotal()).isEqualTo(1);
        assertThat(index.size()).isEqualTo(1);
        assertThat(index.trackedSpanCount()).isEqualTo(1);
    }

    @Test
    void dualSaturationIncrementsOnlyThePerTraceCounterNotTheGlobalOne() {
        // given: per-trace cap and global ledger are both exhausted by the same first span
        SpanPermitLedger ledger = new SpanPermitLedger(1);
        AtomicLong clock = new AtomicLong();
        WatchdogTraceIndex index = index(100, 1, ledger, clock);
        index.admit("t1", "s1", WatchdogTestSpans.span(1, 1), clock.get(), () -> true);

        // when: a second span on the same trace hits per-trace capacity before the ledger is even asked
        index.admit("t1", "s2", WatchdogTestSpans.span(1, 2), clock.get(), () -> true);

        // then
        assertThat(index.rejectedPerTraceTotal()).isEqualTo(1);
        assertThat(index.rejectedGlobalTotal()).isZero();
    }

    @Test
    void permitReleasedOnOnEndIsReusableByANewAdmission() {
        // given
        SpanPermitLedger ledger = new SpanPermitLedger(1);
        AtomicLong clock = new AtomicLong();
        WatchdogTraceIndex index = index(100, 100, ledger, clock);
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
        SpanPermitLedger ledger = new SpanPermitLedger(100);
        AtomicLong clock = new AtomicLong();
        WatchdogTraceIndex index = index(100, 100, ledger, clock);

        // when: authoritative admissionOpen check happens inside compute() and rejects silently
        index.admit("t1", "s1", WatchdogTestSpans.span(1, 1), clock.get(), () -> false);

        // then: not counted as a capacity rejection, and no state/permit leak
        assertThat(index.size()).isZero();
        assertThat(index.trackedSpanCount()).isZero();
        assertThat(index.rejectedPerTraceTotal()).isZero();
        assertThat(index.rejectedGlobalTotal()).isZero();
    }

    // ---------------------------------------------------------------- scan / purge

    @Test
    void collectExpiredPrefersSpanReasonOverTraceReasonWhenBothExpireTogether() {
        // given
        AtomicLong clock = new AtomicLong();
        SpanPermitLedger ledger = new SpanPermitLedger(10);
        WatchdogTraceIndex index = new WatchdogTraceIndex(
                Duration.ofMillis(10).toNanos(), Duration.ofMillis(10).toNanos(), 10, 10, clock::get, ledger);
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
        // given: spanTimeout is generous, traceTimeout is tight
        AtomicLong clock = new AtomicLong();
        SpanPermitLedger ledger = new SpanPermitLedger(10);
        WatchdogTraceIndex index = new WatchdogTraceIndex(
                Duration.ofHours(1).toNanos(), Duration.ofMillis(10).toNanos(), 10, 10, clock::get, ledger);
        index.admit("t1", "s1", WatchdogTestSpans.span(1, 1), clock.get(), () -> true);
        clock.set(Duration.ofMillis(10).toNanos() + 1);

        // when
        List<WatchdogTraceIndex.ExpiredSpan> expired = index.collectExpired(clock.get());

        // then: the span itself is nowhere near its own (huge) timeout, only the trace epoch is
        assertThat(expired).hasSize(1);
        assertThat(expired.getFirst().reason()).isEqualTo(WatchdogTraceIndex.TimeoutReason.TRACE);
    }

    @Test
    void tombstoneSurvivesScanUntilItsOwnTraceEpochExpires() {
        // given: span times out on its own (short spanTimeout), trace epoch (long traceTimeout) has not
        AtomicLong clock = new AtomicLong();
        SpanPermitLedger ledger = new SpanPermitLedger(10);
        long spanTimeout = Duration.ofMillis(10).toNanos();
        long traceTimeout = Duration.ofMillis(100).toNanos();
        WatchdogTraceIndex index = new WatchdogTraceIndex(spanTimeout, traceTimeout, 10, 10, clock::get, ledger);
        index.admit("t1", "s1", WatchdogTestSpans.span(1, 1), clock.get(), () -> true);
        clock.set(spanTimeout + 1);

        // when: the single span is force-closed, but the trace epoch is still young
        List<WatchdogTraceIndex.ExpiredSpan> firstScan = index.collectExpired(clock.get());

        // then: the now-empty trace stays as a tombstone (single authoritative owner, no drop)
        assertThat(firstScan).hasSize(1);
        assertThat(index.size()).isEqualTo(1);
        assertThat(index.nonEmptyTraceCount()).isZero();

        // when: the trace epoch itself finally expires
        clock.set(traceTimeout + 1);
        List<WatchdogTraceIndex.ExpiredSpan> secondScan = index.collectExpired(clock.get());

        // then: the tombstone is removed, without a second force-close
        assertThat(secondScan).isEmpty();
        assertThat(index.size()).isZero();
    }

    @Test
    void hasEndedSpanIsPurgedByScanWithoutForceCloseOrLostPermit() {
        // given: a span that ended through some path OTHER than this index's own onEnd() -
        // e.g. a race window - must still be purged deterministically by hasEnded(), not GC.
        AtomicLong clock = new AtomicLong();
        SpanPermitLedger ledger = new SpanPermitLedger(10);
        WatchdogTraceIndex index = index(10, 10, ledger, clock);
        ReadWriteSpan span = WatchdogTestSpans.span(1, 1);
        index.admit("t1", "s1", span, clock.get(), () -> true);
        assertThat(index.trackedSpanCount()).isEqualTo(1);

        // stub hasEnded() AFTER admission, and never call index.onEnd(): this is exactly the
        // branch `span == null || span.hasEnded()` inside collectTimedOutSpans.
        when(span.hasEnded()).thenReturn(true);

        // when
        List<WatchdogTraceIndex.ExpiredSpan> expired = index.collectExpired(clock.get());

        // then: purged silently, no force-close side effect and the permit is returned
        assertThat(expired).isEmpty();
        assertThat(index.trackedSpanCount()).isZero();
        assertThat(index.nonEmptyTraceCount()).isZero();
    }

    @Test
    void concurrentScanAndOnEndNeverDoubleReleaseTheSamePermit() throws Exception {
        // given
        AtomicLong clock = new AtomicLong();
        SpanPermitLedger ledger = new SpanPermitLedger(10);
        WatchdogTraceIndex index = new WatchdogTraceIndex(
                Duration.ofNanos(1).toNanos(), Duration.ofHours(1).toNanos(), 10, 10, clock::get, ledger);
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

        // when: scan and onEnd race over removing the very same tracked record
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

        // then: exactly one of scan/onEnd wins the compute-serialized removal; the permit is
        // released exactly once, never twice
        assertThat(index.trackedSpanCount()).isZero();
        assertThat(index.ledgerInvariantViolationTotal()).isZero();
    }

    // ---------------------------------------------------------------- eviction

    @Test
    void evictOldestIfNeededEvictsTheComputedBatchAndReleasesTheirPermits() {
        // given: 5 traces, one span each, over a capacity of 3
        SpanPermitLedger ledger = new SpanPermitLedger(100);
        AtomicLong clock = new AtomicLong();
        WatchdogTraceIndex index = index(3, 10, ledger, clock);
        for (int traceOrdinal = 1; traceOrdinal <= 5; traceOrdinal++) {
            clock.set(traceOrdinal);
            index.admit("t" + traceOrdinal, "s1", WatchdogTestSpans.span(traceOrdinal, 1), clock.get(), () -> true);
        }
        assertThat(index.trackedSpanCount()).isEqualTo(5);

        // when: overflow = 2, batch = max(overflow, max(1, capacity/10=0)) = 2
        index.evictOldestIfNeeded();

        // then
        assertThat(index.size()).isEqualTo(3);
        assertThat(index.trackingEvictedTotal()).isEqualTo(2);
        assertThat(index.trackedSpanCount()).isEqualTo(3);

        // and the released permits are usable again
        index.admit("new-trace", "s1", WatchdogTestSpans.span(99, 1), clock.get(), () -> true);
        assertThat(index.trackedSpanCount()).isEqualTo(4);
    }

    @Test
    void admissionStaysFailOpenAboveTraceCapacityUntilEvictionActuallyRuns() {
        // given: soft cap of 1 trace
        SpanPermitLedger ledger = new SpanPermitLedger(100);
        AtomicLong clock = new AtomicLong();
        WatchdogTraceIndex index = index(1, 10, ledger, clock);

        // when: two distinct traces are admitted without ever calling evictOldestIfNeeded()
        index.admit("t1", "s1", WatchdogTestSpans.span(1, 1), clock.get(), () -> true);
        index.admit("t2", "s1", WatchdogTestSpans.span(2, 1), clock.get(), () -> true);

        // then: cardinality is a soft, eviction-backed cap - not a hard admission gate
        assertThat(index.size()).isEqualTo(2);
        assertThat(index.trackedSpanCount()).isEqualTo(2);
    }

    @Test
    void identitySafeEvictionNeverCorruptsTheLedgerUnderConcurrentTraceChurn() throws Exception {
        // given: a small pool of shared traceIds under constant admit+onEnd churn, and a
        // dedicated evictor thread hammering evictOldestIfNeeded() over the same pool -
        // this is exactly the window the identity-safe EvictionCandidate check protects.
        SpanPermitLedger ledger = new SpanPermitLedger(10_000);
        AtomicLong clock = new AtomicLong();
        WatchdogTraceIndex index = index(2, 100, ledger, clock);
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
                            String spanId = WatchdogTestSpans.hex(
                                    capturedThreadIndex * iterationsPerThread + iteration + 1, 16);
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

        // then: the CAS ledger never observes a release beyond what it actually granted
        assertThat(index.ledgerInvariantViolationTotal()).isZero();
    }
}
