package space.br1440.platform.tracing.otel.javaagent.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventType;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ScrubbingLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.event.SpanTimeoutLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

/**
 * Контракт двухфазного ограничения частоты и однократного сообщения о восстановлении.
 */
@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class RateLimitedLoggerTest {

    private static final long ONE_MINUTE_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final AgentLogEventType WARN_PROBLEM =
            SpanTimeoutLogEvent.SPAN_TIMEOUT_SCANNER_PREVIOUSLY_TERMINATED_ABNORMALLY;
    private static final AgentLogEventType ERROR_PROBLEM = ScrubbingLogEvent.SCRUBBING_BOUNDARY_COLLAPSED;
    private static final AgentLogEventType ERROR_WITH_CAUSE = ScrubbingLogEvent.SCRUB_BOUNDARY_FAILED;
    private static final AgentLogFieldsWriter NO_FIELDS = fields -> {
    };

    private final AgentLogCapture capture = new AgentLogCapture();

    @BeforeEach
    void installCapture() {
        capture.install();
        capture.applyLevel(AgentLogLevel.INFO);
    }

    @AfterEach
    void restoreRuntime() {
        capture.restore();
    }

    @Test
    void transitionsFromFastBucketToOnePerIntervalWithSingleMarker() {
        // given
        AtomicLong clock = new AtomicLong();
        RateLimitedLogger logger = logger(clock, 2.0d);

        // when
        EmitResult first = warn(logger);
        EmitResult second = warn(logger);
        EmitResult transition = warn(logger);
        EmitResult suppressed = warn(logger);
        clock.set(ONE_MINUTE_NANOS - 1L);
        EmitResult beforeBoundary = warn(logger);
        clock.set(ONE_MINUTE_NANOS);
        EmitResult atBoundary = warn(logger);

        // then
        assertThat(first).isEqualTo(EmitResult.EMITTED);
        assertThat(second).isEqualTo(EmitResult.EMITTED);
        assertThat(transition).isEqualTo(EmitResult.EMITTED);
        assertThat(suppressed).isEqualTo(EmitResult.RATE_LIMITED);
        assertThat(beforeBoundary).isEqualTo(EmitResult.RATE_LIMITED);
        assertThat(atBoundary).isEqualTo(EmitResult.EMITTED);
        assertThat(logger.isThrottled()).isTrue();
        assertThat(capture.events())
                .filteredOn(event -> event.eventCode() == AgentEventCode.RATE_THROTTLED)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.level()).isEqualTo(AgentLogLevel.WARN);
                    assertThat(event.fields()).containsEntry("kind", "warnings");
                });
        assertThat(capture.events())
                .filteredOn(event -> event.eventCode() == AgentEventCode.EXPORT_SPAN_TIMEOUT)
                .last()
                .extracting(CapturedLogEvent::suppressedCount)
                .isEqualTo(2L);
    }

    @Test
    void concurrentTransitionHasOneMarkerAndOneSlowAdmission() throws Exception {
        // given
        int threadCount = 32;
        AtomicLong clock = new AtomicLong();
        RateLimitedLogger logger = logger(clock, 2.0d);
        assertThat(warn(logger))
                .isEqualTo(EmitResult.EMITTED);
        assertThat(warn(logger))
                .isEqualTo(EmitResult.EMITTED);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<EmitResult>> futures = new ArrayList<>(threadCount);
        for (int index = 0; index < threadCount; index++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return warn(logger);
            }));
        }

        // when
        List<EmitResult> results = new ArrayList<>(threadCount);
        try {
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<EmitResult> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        // then
        assertThat(results).filteredOn(EmitResult.EMITTED::equals).hasSize(1);
        assertThat(results).filteredOn(EmitResult.RATE_LIMITED::equals).hasSize(threadCount - 1);
        assertThat(capture.events())
                .filteredOn(event -> event.eventCode() == AgentEventCode.RATE_THROTTLED)
                .hasSize(1);
        assertThat(capture.events())
                .filteredOn(event -> event.eventCode() == AgentEventCode.EXPORT_SPAN_TIMEOUT)
                .hasSize(3);
    }

    @Test
    void warnAndErrorUseIndependentThrottlesAndMatchingMarkerLevels() {
        // given
        AtomicLong clock = new AtomicLong();
        RateLimitedLogger logger = logger(clock, 1.0d);

        // when
        EmitResult warnFast = warn(logger);
        EmitResult warnSlow = warn(logger);
        EmitResult warnDenied = warn(logger);
        EmitResult errorFast = error(logger);
        EmitResult errorSlow = error(logger);
        EmitResult errorDenied = error(logger);

        // then
        assertThat(List.of(warnFast, warnSlow, errorFast, errorSlow))
                .containsOnly(EmitResult.EMITTED);
        assertThat(warnDenied).isEqualTo(EmitResult.RATE_LIMITED);
        assertThat(errorDenied).isEqualTo(EmitResult.RATE_LIMITED);
        List<CapturedLogEvent> markers = capture.events().stream()
                .filter(event -> event.eventCode() == AgentEventCode.RATE_THROTTLED)
                .toList();
        assertThat(markers).extracting(CapturedLogEvent::level)
                .containsExactly(AgentLogLevel.WARN, AgentLogLevel.ERROR);
        assertThat(markers).extracting(event -> event.fields().get("kind"))
                .containsExactly("warnings", "errors");
    }

    @Test
    void errorMarkerPassesErrorThresholdWhileWarnDoesNotConsumePermit() {
        // given
        AtomicLong clock = new AtomicLong();
        RateLimitedLogger logger = logger(clock, 1.0d);
        capture.applyLevel(AgentLogLevel.ERROR);

        // when
        EmitResult filteredWarn = warn(logger);
        EmitResult firstError = error(logger);
        EmitResult secondError = error(logger);

        // then
        assertThat(filteredWarn).isEqualTo(EmitResult.FILTERED_BY_LEVEL);
        assertThat(firstError).isEqualTo(EmitResult.EMITTED);
        assertThat(secondError).isEqualTo(EmitResult.EMITTED);
        assertThat(capture.events())
                .filteredOn(event -> event.eventCode() == AgentEventCode.RATE_THROTTLED)
                .singleElement()
                .extracting(CapturedLogEvent::level)
                .isEqualTo(AgentLogLevel.ERROR);
    }

    @Test
    void markerDeliveryFailureDoesNotStopProblemAndArmsRecovery() {
        // given
        AtomicLong clock = new AtomicLong();
        FailingWriteOutputStream stream = new FailingWriteOutputStream(3);
        RateLimitedLogger logger = isolatedLogger(stream, clock, 1.0d);
        assertThat(warn(logger))
                .isEqualTo(EmitResult.EMITTED);
        assertThat(logger.tryLogRecoveryOnce()).isTrue();

        // when
        EmitResult transition = warn(logger);

        // then
        assertThat(transition).isEqualTo(EmitResult.EMITTED);
        assertThat(stream.writeAttempts()).isEqualTo(4);
        assertThat(occurrences(stream.payload(), AgentEventCode.EXPORT_SPAN_TIMEOUT.code())).isEqualTo(2);
        assertThat(logger.tryLogRecoveryOnce()).isTrue();
    }

    @Test
    void problemDeliveryFailureAfterMarkerDoesNotArmRecovery() {
        // given
        AtomicLong clock = new AtomicLong();
        FailingWriteOutputStream stream = new FailingWriteOutputStream(4);
        RateLimitedLogger logger = isolatedLogger(stream, clock, 1.0d);
        assertThat(warn(logger))
                .isEqualTo(EmitResult.EMITTED);
        assertThat(logger.tryLogRecoveryOnce()).isTrue();

        // when
        EmitResult transition = warn(logger);

        // then
        assertThat(transition).isEqualTo(EmitResult.DELIVERY_FAILED);
        assertThat(stream.writeAttempts()).isEqualTo(4);
        assertThat(occurrences(stream.payload(), AgentEventCode.RATE_THROTTLED.code())).isOne();
        assertThat(occurrences(stream.payload(), AgentEventCode.EXPORT_SPAN_TIMEOUT.code())).isOne();
        assertThat(logger.tryLogRecoveryOnce()).isFalse();
    }

    @Test
    void nestedLogFromMarkerSinkIsRejectedAfterConsumingItsPermit() {
        // given
        AtomicLong clock = new AtomicLong();
        AtomicReference<RateLimitedLogger> loggerReference = new AtomicReference<>();
        AtomicReference<EmitResult> nestedResult = new AtomicReference<>();
        AtomicBoolean nested = new AtomicBoolean();
        RecordingOutputStream stream = new RecordingOutputStream(payload -> {
            if (payload.contains(AgentEventCode.RATE_THROTTLED.code())
                    && nested.compareAndSet(false, true)) {
                nestedResult.set(error(loggerReference.get()));
            }
        });
        RateLimitedLogger logger = isolatedLogger(stream, clock, 1.0d);
        loggerReference.set(logger);
        assertThat(warn(logger))
                .isEqualTo(EmitResult.EMITTED);

        // when
        EmitResult warnTransition = warn(logger);
        EmitResult errorAfterNested = error(logger);

        // then
        assertThat(warnTransition).isEqualTo(EmitResult.EMITTED);
        assertThat(nestedResult.get()).isEqualTo(EmitResult.DELIVERY_FAILED);
        assertThat(errorAfterNested).isEqualTo(EmitResult.EMITTED);
        assertThat(stream.writeAttempts()).isEqualTo(5);
        assertThat(occurrences(stream.payload(), AgentEventCode.RATE_THROTTLED.code())).isEqualTo(2);
        assertThat(occurrences(stream.payload(), AgentEventCode.SCRUB_BOUNDARY_COLLAPSE.code())).isOne();
    }

    @Test
    void throwableRenderingRunsOnlyAfterAThrottleAdmission() {
        // given
        AtomicLong clock = new AtomicLong();
        AtomicInteger renderCalls = new AtomicInteger();
        RateLimitedLogger logger = logger(clock, 1.0d);
        CountingThrowable failure = new CountingThrowable(renderCalls);
        assertThat(error(logger))
                .isEqualTo(EmitResult.EMITTED);

        // when
        EmitResult transition = errorWithCause(logger, failure);
        EmitResult denied = errorWithCause(logger, failure);
        clock.set(ONE_MINUTE_NANOS);
        EmitResult nextInterval = errorWithCause(logger, failure);

        // then
        assertThat(transition).isEqualTo(EmitResult.EMITTED);
        assertThat(denied).isEqualTo(EmitResult.RATE_LIMITED);
        assertThat(nextInterval).isEqualTo(EmitResult.EMITTED);
        assertThat(renderCalls).hasValue(2);
    }

    @Test
    void recoveryIsArmedOnlyByEmittedProblemsAndCollapsesUntilConsumed() {
        // given
        AtomicLong clock = new AtomicLong();
        RateLimitedLogger logger = logger(clock, 5.0d);

        // when
        boolean beforeProblem = logger.tryLogRecoveryOnce();
        EmitResult first = warn(logger);
        EmitResult second = warn(logger);
        boolean firstRecovery = logger.tryLogRecoveryOnce();
        boolean duplicateRecovery = logger.tryLogRecoveryOnce();
        EmitResult third = warn(logger);
        boolean nextRecovery = logger.tryLogRecoveryOnce();

        // then
        assertThat(beforeProblem).isFalse();
        assertThat(List.of(first, second, third)).containsOnly(EmitResult.EMITTED);
        assertThat(firstRecovery).isTrue();
        assertThat(duplicateRecovery).isFalse();
        assertThat(nextRecovery).isTrue();
        assertThat(capture.events())
                .filteredOn(event -> event.eventCode() == AgentEventCode.RATE_RECOVERY)
                .hasSize(2)
                .allSatisfy(event -> assertThat(event.message()).isEqualTo(
                        "SafeSpanExporter: export recovered after a series of failures"
                ));
    }

    @Test
    void recoveryReportsEventsSuppressedSinceTheLastAdmission() {
        // given
        AtomicLong clock = new AtomicLong();
        RateLimitedLogger logger = logger(clock, 1.0d);
        assertThat(warn(logger)).isEqualTo(EmitResult.EMITTED);
        assertThat(warn(logger)).isEqualTo(EmitResult.EMITTED);
        assertThat(warn(logger)).isEqualTo(EmitResult.RATE_LIMITED);
        assertThat(warn(logger)).isEqualTo(EmitResult.RATE_LIMITED);

        // when
        boolean recovery = logger.tryLogRecoveryOnce();

        // then
        assertThat(recovery).isTrue();
        assertThat(capture.events())
                .filteredOn(event -> event.eventCode() == AgentEventCode.RATE_RECOVERY)
                .singleElement()
                .extracting(CapturedLogEvent::suppressedCount)
                .isEqualTo(2L);
    }

    @Test
    void filteredRecoveryConsumesThePendingAttempt() {
        // given
        AtomicLong clock = new AtomicLong();
        RateLimitedLogger logger = logger(clock, 5.0d);
        assertThat(warn(logger))
                .isEqualTo(EmitResult.EMITTED);
        capture.applyLevel(AgentLogLevel.ERROR);

        // when
        boolean filteredAttempt = logger.tryLogRecoveryOnce();
        capture.applyLevel(AgentLogLevel.INFO);
        boolean repeatedAttempt = logger.tryLogRecoveryOnce();

        // then
        assertThat(filteredAttempt).isTrue();
        assertThat(repeatedAttempt).isFalse();
        assertThat(capture.events())
                .noneMatch(event -> event.eventCode() == AgentEventCode.RATE_RECOVERY);
    }

    @Test
    void deliveryFailedRecoveryConsumesThePendingAttempt() {
        // given
        AtomicLong clock = new AtomicLong();
        FailingWriteOutputStream stream = new FailingWriteOutputStream(2);
        RateLimitedLogger logger = isolatedLogger(stream, clock, 5.0d);
        assertThat(warn(logger))
                .isEqualTo(EmitResult.EMITTED);

        // when
        boolean failedAttempt = logger.tryLogRecoveryOnce();
        boolean repeatedAttempt = logger.tryLogRecoveryOnce();

        // then
        assertThat(failedAttempt).isTrue();
        assertThat(repeatedAttempt).isFalse();
        assertThat(stream.writeAttempts()).isEqualTo(2);
    }

    @Test
    void fallbackArmsRecoveryWhileDeliveryFailureDoesNot() {
        // given
        AtomicLong clock = new AtomicLong();
        RateLimitedLogger fallbackLogger = logger(clock, 5.0d);
        FailingWriteOutputStream stream = new FailingWriteOutputStream(1);
        RateLimitedLogger deliveryLogger = isolatedLogger(stream, new AtomicLong(), 5.0d);

        // when
        EmitResult fallback = fallbackLogger.logWithResult(
                WARN_PROBLEM,
                fields -> fields.putString(AgentLogField.PROFILE, "secret")
        );
        EmitResult deliveryFailure = warn(deliveryLogger);

        // then
        assertThat(fallback).isEqualTo(EmitResult.FALLBACK_EMITTED);
        assertThat(deliveryFailure).isEqualTo(EmitResult.DELIVERY_FAILED);
        assertThat(fallbackLogger.tryLogRecoveryOnce()).isTrue();
        assertThat(deliveryLogger.tryLogRecoveryOnce()).isFalse();
    }

    private static EmitResult warn(RateLimitedLogger logger) {
        return logger.logWithResult(WARN_PROBLEM, NO_FIELDS);
    }

    private static EmitResult error(RateLimitedLogger logger) {
        return logger.logWithResult(ERROR_PROBLEM, NO_FIELDS);
    }

    private static EmitResult errorWithCause(RateLimitedLogger logger, Throwable failure) {
        return logger.logWithResult(ERROR_WITH_CAUSE, fields -> fields.cause(failure));
    }

    private static RateLimitedLogger logger(AtomicLong clock, double fastLimit) {
        return new RateLimitedLogger(
                PlatformAgentLogger.get(RateLimitedLoggerTest.class),
                fastLimit,
                1.0d,
                1L,
                TimeUnit.MINUTES,
                clock::get
        );
    }

    private static RateLimitedLogger isolatedLogger(OutputStream stream,
                                                    AtomicLong clock,
                                                    double fastLimit) {
        AgentLoggingRuntime runtime = new AgentLoggingRuntime(
                new PlatformLogEventEncoder(),
                new PlatformStderrSink(stream),
                AgentLogLevel.TRACE
        );
        return new RateLimitedLogger(
                new PlatformAgentLogger(RateLimitedLoggerTest.class.getName(), runtime),
                fastLimit,
                1.0d,
                1L,
                TimeUnit.MINUTES,
                clock::get
        );
    }

    private static int occurrences(String value, String fragment) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(fragment, index)) >= 0) {
            count++;
            index += fragment.length();
        }
        return count;
    }

    private static final class CountingThrowable extends RuntimeException {

        private final AtomicInteger renderCalls;

        private CountingThrowable(AtomicInteger renderCalls) {
            super("simulated failure");
            this.renderCalls = renderCalls;
        }

        @Override
        public StackTraceElement[] getStackTrace() {
            renderCalls.incrementAndGet();
            return new StackTraceElement[0];
        }
    }

    private static final class FailingWriteOutputStream extends OutputStream {

        private final int failedAttempt;
        private final AtomicInteger writeAttempts = new AtomicInteger();
        private final ByteArrayOutputStream payload = new ByteArrayOutputStream();

        private FailingWriteOutputStream(int failedAttempt) {
            this.failedAttempt = failedAttempt;
        }

        @Override
        public void write(int value) {
            payload.write(value);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            if (writeAttempts.incrementAndGet() == failedAttempt) {
                throw new IOException("Simulated log delivery failure");
            }
            payload.write(buffer, offset, length);
        }

        int writeAttempts() {
            return writeAttempts.get();
        }

        String payload() {
            return payload.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class RecordingOutputStream extends OutputStream {

        private final Consumer<String> callback;
        private final AtomicInteger writeAttempts = new AtomicInteger();
        private final ByteArrayOutputStream payload = new ByteArrayOutputStream();

        private RecordingOutputStream(Consumer<String> callback) {
            this.callback = callback;
        }

        @Override
        public void write(int value) {
            payload.write(value);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            writeAttempts.incrementAndGet();
            payload.write(buffer, offset, length);
            callback.accept(new String(buffer, offset, length, StandardCharsets.UTF_8));
        }

        int writeAttempts() {
            return writeAttempts.get();
        }

        String payload() {
            return payload.toString(StandardCharsets.UTF_8);
        }
    }
}
