package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class SpanTimeoutScanRobustnessTest {

    private static final Duration SPAN_TIMEOUT = Duration.ofNanos(1);
    private static final Duration HOUR = Duration.ofHours(1);

    private static SpanTimeoutProcessor manualProcessor(LongSupplier nanoTime, int maxTrackedTraces) {
        return new SpanTimeoutProcessor(SPAN_TIMEOUT, HOUR, nanoTime, maxTrackedTraces, 10, 10);
    }

    @Test
    void recoverableFailureOfOneCandidateDoesNotLoseTheRestOfTheBatch() {
        // given
        AtomicLong clock = new AtomicLong();
        ReadWriteSpan first = SpanTimeoutTestSpans.span(1, 1);
        ReadWriteSpan failing = SpanTimeoutTestSpans.span(2, 1);
        ReadWriteSpan third = SpanTimeoutTestSpans.span(3, 1);
        doThrow(new IllegalStateException("injected end failure")).when(failing).end();

        try (SpanTimeoutProcessor processor = manualProcessor(clock::get, 10)) {
            for (ReadWriteSpan span : List.of(first, failing, third)) {
                processor.onStart(Context.root(), span);
            }
            clock.set(2L);

            // when
            boolean success = processor.forceFlush().isSuccess();

            // then
            assertThat(success).isFalse();
            assertThat(processor.getForceEndFailuresTotal()).isEqualTo(1);
            assertThat(processor.getScanFailuresTotal()).isEqualTo(1);
            assertThat(processor.getForcedSpanEndAttemptsTotal()).isEqualTo(3);
            assertThat(processor.getActiveSpanCount()).isZero();
            assertThat(processor.getSpanPermitInvariantViolationTotal()).isZero();
            verify(first).end();
            verify(failing).end();
            verify(third).end();
        }
    }

    @Test
    void collectionFailureAfterExtractionKeepsBothTheExtractedCandidateAndTheRemainder() {
        // given
        AtomicLong clock = new AtomicLong();
        AtomicInteger hasEndedCalls = new AtomicInteger();
        AtomicBoolean failCollection = new AtomicBoolean(true);
        ReadWriteSpan spanA = SpanTimeoutTestSpans.span(1, 1);
        ReadWriteSpan spanB = SpanTimeoutTestSpans.span(2, 1);
        // Первый опрошенный span извлекается, второй роняет этап collection.
        for (ReadWriteSpan span : List.of(spanA, spanB)) {
            when(span.hasEnded()).thenAnswer(ignored -> {
                if (failCollection.get() && (hasEndedCalls.incrementAndGet() == 2)) {
                    throw new AssertionError("injected collection failure");
                }

                return false;
            });
        }

        try (SpanTimeoutProcessor processor = manualProcessor(clock::get, 10)) {
            processor.onStart(Context.root(), spanA);
            processor.onStart(Context.root(), spanB);
            clock.set(2L);

            // when
            boolean firstPass = processor.forceFlush().isSuccess();

            // then
            assertThat(firstPass).isFalse();
            assertThat(processor.getScanFailuresTotal()).isEqualTo(1);
            assertThat(processor.getForceEndFailuresTotal()).isZero();
            assertThat(processor.getForcedSpanEndAttemptsTotal()).isEqualTo(1);
            assertThat(processor.getActiveSpanCount()).isEqualTo(1);
            assertThat(processor.getSpanPermitInvariantViolationTotal()).isZero();

            // when
            failCollection.set(false);
            boolean secondPass = processor.forceFlush().isSuccess();

            // then
            assertThat(secondPass).isTrue();
            assertThat(processor.getForcedSpanEndAttemptsTotal()).isEqualTo(2);
            assertThat(processor.getActiveSpanCount()).isZero();
            assertThat(processor.getScanFailuresTotal()).isEqualTo(1);
        }
    }

    @Test
    void reliefAndCollectionFailuresWithinOnePassIncrementScanFailuresOnce() {
        // given
        AtomicLong clock = new AtomicLong();
        AtomicBoolean clockBroken = new AtomicBoolean();
        LongSupplier nanoTime = () -> {
            if (clockBroken.get()) {
                throw new AssertionError("injected clock failure");
            }

            return clock.get();
        };
        ReadWriteSpan ended = SpanTimeoutTestSpans.span(1, 1);

        try (SpanTimeoutProcessor processor = manualProcessor(nanoTime, 1)) {
            processor.onStart(Context.root(), ended);
            processor.onEnd(ended);
            processor.onStart(Context.root(), SpanTimeoutTestSpans.span(2, 1));
            assertThat(processor.getSpansRejectedTraceCapacityTotal()).isEqualTo(1);
            clockBroken.set(true);

            // when
            boolean success = processor.forceFlush().isSuccess();

            // then
            assertThat(success).isFalse();
            assertThat(processor.getScanFailuresTotal()).isEqualTo(1);
            assertThat(processor.getTrackingEvictedTotal()).isEqualTo(1);
            assertThat(processor.getForceEndFailuresTotal()).isZero();
        }
    }

    @Test
    void fatalErrorFromManualScanReturnsRemainingPermitsWithoutEndingTheRemainder() {
        // given
        AtomicLong clock = new AtomicLong();
        AtomicInteger endCalls = new AtomicInteger();
        List<ReadWriteSpan> spans = List.of(
                SpanTimeoutTestSpans.span(1, 1),
                SpanTimeoutTestSpans.span(2, 1),
                SpanTimeoutTestSpans.span(3, 1));
        // Первый end() прохода — синтетический фатальный LinkageError; остальные лишь считаются.
        for (ReadWriteSpan span : spans) {
            doAnswer(ignored -> {
                if (endCalls.incrementAndGet() == 1) {
                    throw new LinkageError("injected fatal failure");
                }

                return null;
            }).when(span).end();
        }

        try (SpanTimeoutProcessor processor = manualProcessor(clock::get, 10)) {
            for (ReadWriteSpan span : spans) {
                processor.onStart(Context.root(), span);
            }
            clock.set(2L);

            // when/then
            assertThatThrownBy(processor::forceFlush)
                    .isInstanceOf(LinkageError.class)
                    .hasMessage("injected fatal failure");

            assertThat(endCalls.get()).isEqualTo(1);
            assertThat(processor.getActiveSpanCount()).isZero();
            assertThat(processor.getScanFailuresTotal()).isEqualTo(1);
            assertThat(processor.getForceEndFailuresTotal()).isZero();
            assertThat(processor.getScannerState()).isEqualTo(SpanTimeoutScannerState.DISABLED);
            assertThat(processor.getSpanPermitInvariantViolationTotal()).isZero();

            processor.onStart(Context.root(), SpanTimeoutTestSpans.span(4, 1));
            assertThat(processor.getActiveSpanCount()).isEqualTo(1);
            assertThat(processor.getSpansRejectedGlobalTotal()).isZero();
        }
    }

    @Test
    @SuppressWarnings("removal")
    void threadDeathFromManualScanIsPropagated() {
        // given
        AtomicLong clock = new AtomicLong();
        ReadWriteSpan span = SpanTimeoutTestSpans.span(1, 1);
        ThreadDeath failure = new ThreadDeath();
        doThrow(failure).when(span).end();

        try (SpanTimeoutProcessor processor = manualProcessor(clock::get, 10)) {
            processor.onStart(Context.root(), span);
            clock.set(2L);

            // when
            // then
            assertThatThrownBy(processor::forceFlush).isSameAs(failure);
            assertThat(processor.getForceEndFailuresTotal()).isZero();
            assertThat(processor.getScannerState()).isEqualTo(SpanTimeoutScannerState.DISABLED);
        }
    }

    @Test
    void cleanPassEmitsExactlyOneInfoSummary() {
        // given
        AtomicLong clock = new AtomicLong();
        try (AgentLogCapture logs = AgentLogCapture.installed();
             SpanTimeoutProcessor processor = manualProcessor(clock::get, 10)) {
            for (int ordinal = 1; ordinal <= 3; ordinal++) {
                processor.onStart(Context.root(), SpanTimeoutTestSpans.span(ordinal, 1));
            }
            clock.set(2L);

            // when
            processor.forceFlush();

            // then
            List<CapturedLogEvent> summaries = processorSummaries(logs);
            assertThat(summaries).hasSize(1);
            CapturedLogEvent summary = summaries.getFirst();
            assertThat(summary.level()).isEqualTo(AgentLogLevel.INFO);
            assertThat(summary.eventCode()).isEqualTo(AgentEventCode.EXPORT_SPAN_TIMEOUT);
            assertThat(summary.message()).isEqualTo("Span timeout force-ended spans");
            assertThat(summary.fields())
                    .containsEntry("spanTimeoutAttempts", "3")
                    .containsEntry("traceTimeoutAttempts", "0")
                    .containsEntry("failures", "0");
        }
    }

    @Test
    void passWithCandidateFailuresEmitsExactlyOneWarnSummaryWithTheFailureType() {
        // given
        AtomicLong clock = new AtomicLong();
        ReadWriteSpan failing = SpanTimeoutTestSpans.span(1, 1);
        doThrow(new IllegalStateException("injected end failure")).when(failing).end();
        try (AgentLogCapture logs = AgentLogCapture.installed();
             SpanTimeoutProcessor processor = manualProcessor(clock::get, 10)) {
            processor.onStart(Context.root(), failing);
            processor.onStart(Context.root(), SpanTimeoutTestSpans.span(2, 1));
            clock.set(2L);

            // when
            processor.forceFlush();

            // then
            List<CapturedLogEvent> summaries = processorSummaries(logs);
            assertThat(summaries).hasSize(1);
            CapturedLogEvent summary = summaries.getFirst();
            assertThat(summary.level()).isEqualTo(AgentLogLevel.WARN);
            assertThat(summary.message()).isEqualTo("Span timeout force-ended spans with failures");
            assertThat(summary.fields())
                    .containsEntry("spanTimeoutAttempts", "2")
                    .containsEntry("failures", "1")
                    .containsEntry("failureSample", IllegalStateException.class.getName());
            assertThat(summary.fields().get("failureSample")).doesNotContain("injected end failure");
        }
    }

    @Test
    void failureSummaryKeepsTheFirstFailureTuple() {
        // given
        AtomicLong clock = new AtomicLong();
        ReadWriteSpan first = SpanTimeoutTestSpans.span(1, 1);
        ReadWriteSpan second = SpanTimeoutTestSpans.span(2, 1);
        doThrow(new IllegalStateException("first failure")).when(first).end();
        doThrow(new UnsupportedOperationException("second failure")).when(second).end();
        try (AgentLogCapture logs = AgentLogCapture.installed();
             SpanTimeoutProcessor processor = manualProcessor(clock::get, 10)) {
            processor.onStart(Context.root(), first);
            processor.onStart(Context.root(), second);
            clock.set(2L);

            // when
            processor.forceFlush();

            // then
            List<CapturedLogEvent> failures = logs.events().stream()
                    .filter(event -> "Span timeout failed to force-end span".equals(event.message()))
                    .toList();
            CapturedLogEvent firstFailure = failures.getFirst();
            CapturedLogEvent summary = processorSummaries(logs).getFirst();
            assertThat(failures).hasSize(2);
            assertThat(summary.fields())
                    .containsEntry("failures", "2")
                    .containsEntry("sampleTraceId", firstFailure.fields().get("traceId"))
                    .containsEntry("sampleSpanId", firstFailure.fields().get("spanId"))
                    .containsEntry("failureSample", firstFailure.fields().get("failureType"));
        }
    }

    private static List<CapturedLogEvent> processorSummaries(AgentLogCapture logs) {
        return logs.events().stream()
                .filter(event -> SpanTimeoutScan.class.getName().equals(event.logger()))
                .filter(event -> event.level() != AgentLogLevel.DEBUG)
                .toList();
    }
}
