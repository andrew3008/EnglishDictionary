package space.br1440.platform.tracing.otel.javaagent.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventType;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ScrubbingLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.event.SpanTimeoutLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class RateLimitedLoggerTest {

    private static final long ONE_MINUTE_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final AgentLogEventType WARN_PROBLEM =
            SpanTimeoutLogEvent.SPAN_TIMEOUT_SCANNER_PREVIOUSLY_TERMINATED_ABNORMALLY;
    private static final AgentLogEventType ERROR_PROBLEM = ScrubbingLogEvent.SCRUBBING_BOUNDARY_COLLAPSED;
    private static final AgentLogEventType ERROR_WITH_CAUSE = ScrubbingLogEvent.SCRUB_BOUNDARY_FAILED;

    @RegisterExtension
    final AgentLogCapture capture = new AgentLogCapture();

    @BeforeEach
    void enableInfo() {
        capture.applyLevel(AgentLogLevel.INFO);
    }

    @Test
    void transitionsFromFastBucketToOnePerIntervalWithSingleMarker() {
        // given
        AtomicLong clock = new AtomicLong();
        RateLimitedLogger logger = logger(clock, 2);

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
        RateLimitedLogger logger = logger(clock, 2);
        assertThat(warn(logger)).isEqualTo(EmitResult.EMITTED);
        assertThat(warn(logger)).isEqualTo(EmitResult.EMITTED);

        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<EmitResult> results = new ArrayList<>(threadCount);
        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            List<Future<EmitResult>> futures = new ArrayList<>(threadCount);
            for (int index = 0; index < threadCount; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return warn(logger);
                }));
            }

            // when
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<EmitResult> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
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
        RateLimitedLogger logger = logger(clock, 1);

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
        RateLimitedLogger logger = logger(clock, 1);
        capture.applyLevel(AgentLogLevel.ERROR);

        // when
        EmitResult filteredWarn = warn(logger);
        EmitResult firstError = error(logger);
        EmitResult secondError = error(logger);
        capture.applyLevel(AgentLogLevel.INFO);
        EmitResult firstWarn = warn(logger);
        EmitResult secondWarn = warn(logger);

        // then
        assertThat(filteredWarn).isEqualTo(EmitResult.FILTERED_BY_LEVEL);
        assertThat(List.of(firstError, secondError, firstWarn, secondWarn)).containsOnly(EmitResult.EMITTED);
        assertThat(capture.events())
                .filteredOn(event -> event.eventCode() == AgentEventCode.RATE_THROTTLED)
                .extracting(CapturedLogEvent::level)
                .containsExactly(AgentLogLevel.ERROR, AgentLogLevel.WARN);
    }

    @Test
    void markerDeliveryFailureDoesNotStopProblem() {
        // given
        AtomicLong clock = new AtomicLong();
        FailingWriteOutputStream stream = new FailingWriteOutputStream(2);
        RateLimitedLogger logger = isolatedLogger(stream, clock, 1);
        assertThat(warn(logger))
                .isEqualTo(EmitResult.EMITTED);

        // when
        EmitResult transition = warn(logger);

        // then
        assertThat(transition).isEqualTo(EmitResult.EMITTED);
        assertThat(stream.writeAttempts()).isEqualTo(3);
        assertThat(occurrences(stream.payload(), AgentEventCode.EXPORT_SPAN_TIMEOUT.code())).isEqualTo(2);
    }

    @Test
    void problemDeliveryFailureAfterMarkerKeepsMarkerDelivered() {
        // given
        AtomicLong clock = new AtomicLong();
        FailingWriteOutputStream stream = new FailingWriteOutputStream(3);
        RateLimitedLogger logger = isolatedLogger(stream, clock, 1);
        assertThat(warn(logger))
                .isEqualTo(EmitResult.EMITTED);

        // when
        EmitResult transition = warn(logger);

        // then
        assertThat(transition).isEqualTo(EmitResult.DELIVERY_FAILED);
        assertThat(stream.writeAttempts()).isEqualTo(3);
        assertThat(occurrences(stream.payload(), AgentEventCode.RATE_THROTTLED.code())).isOne();
        assertThat(occurrences(stream.payload(), AgentEventCode.EXPORT_SPAN_TIMEOUT.code())).isOne();
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
        RateLimitedLogger logger = isolatedLogger(stream, clock, 1);
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
        RateLimitedLogger logger = logger(clock, 1);
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
    void healedThrottleCanEmitASecondTransitionMarker() {
        // given
        AtomicLong clock = new AtomicLong();
        RateLimitedLogger logger = logger(clock, 1);
        assertThat(warn(logger)).isEqualTo(EmitResult.EMITTED);
        assertThat(warn(logger)).isEqualTo(EmitResult.EMITTED);
        assertThat(warn(logger)).isEqualTo(EmitResult.RATE_LIMITED);

        // when
        clock.set(ONE_MINUTE_NANOS);
        EmitResult healed = warn(logger);
        EmitResult repeatedTransition = warn(logger);

        // then
        assertThat(healed).isEqualTo(EmitResult.EMITTED);
        assertThat(repeatedTransition).isEqualTo(EmitResult.EMITTED);
        assertThat(capture.events())
                .filteredOn(event -> event.eventCode() == AgentEventCode.RATE_THROTTLED)
                .hasSize(2);
        assertThat(capture.events())
                .filteredOn(event -> event.eventCode() == AgentEventCode.EXPORT_SPAN_TIMEOUT)
                .extracting(CapturedLogEvent::suppressedCount)
                .containsExactly(0L, 0L, 1L, 0L);
    }

    @Test
    void fallbackAndDeliveryFailureReportTheirEmitResults() {
        // given
        AtomicLong clock = new AtomicLong();
        RateLimitedLogger fallbackLogger = logger(clock, 5);
        FailingWriteOutputStream stream = new FailingWriteOutputStream(1);
        RateLimitedLogger deliveryLogger = isolatedLogger(stream, new AtomicLong(), 5);

        // when
        EmitResult fallback = fallbackLogger.logWithResult(
                WARN_PROBLEM,
                fields -> fields.putString(AgentLogField.PROFILE, "secret")
        );
        EmitResult deliveryFailure = warn(deliveryLogger);

        // then
        assertThat(fallback).isEqualTo(EmitResult.FALLBACK_EMITTED);
        assertThat(deliveryFailure).isEqualTo(EmitResult.DELIVERY_FAILED);
    }

    private static EmitResult warn(RateLimitedLogger logger) {
        return logger.logWithResult(WARN_PROBLEM, AgentLogFieldsWriter.NONE);
    }

    private static EmitResult error(RateLimitedLogger logger) {
        return logger.logWithResult(ERROR_PROBLEM, AgentLogFieldsWriter.NONE);
    }

    private static EmitResult errorWithCause(RateLimitedLogger logger, Throwable failure) {
        return logger.logWithResult(ERROR_WITH_CAUSE, fields -> fields.cause(failure));
    }

    private static RateLimitedLogger logger(AtomicLong clock, int burst) {
        return new RateLimitedLogger(
                PlatformAgentLogger.get(RateLimitedLoggerTest.class),
                burst,
                Duration.ofMinutes(1L),
                clock::get
        );
    }

    private static RateLimitedLogger isolatedLogger(OutputStream stream,
                                                    AtomicLong clock,
                                                    int burst) {
        AgentLoggingRuntime runtime = new AgentLoggingRuntime(
                new PlatformLogEventEncoder(),
                new PlatformStderrSink(stream),
                AgentLogLevel.TRACE
        );
        return new RateLimitedLogger(
                new PlatformAgentLogger(RateLimitedLoggerTest.class.getName(), runtime),
                burst,
                Duration.ofMinutes(1L),
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
        public void write(@Nonnull byte[] buffer, int offset, int length) throws IOException {
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
        public void write(@Nonnull byte[] buffer, int offset, int length) {
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
