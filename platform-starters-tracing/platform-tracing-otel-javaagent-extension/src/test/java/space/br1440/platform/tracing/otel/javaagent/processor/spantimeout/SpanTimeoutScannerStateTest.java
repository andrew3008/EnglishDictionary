package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class SpanTimeoutScannerStateTest {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SCAN_INTERVAL = Duration.ofMillis(10);
    private static final long FIXED_EPOCH_MILLIS = 1_234L;

    private static SpanTimeoutProcessor started(Duration scanInterval, LongSupplier nanoTime) {
        return SpanTimeoutProcessor.start(
                Duration.ofHours(1), Duration.ofHours(1), scanInterval,
                nanoTime, () -> FIXED_EPOCH_MILLIS,
                10, 10, 10
        );
    }

    @Test
    void manualProcessorWithoutSchedulerReportsDisabled() {
        // given
        try (SpanTimeoutProcessor processor = new SpanTimeoutProcessor(
                Duration.ofHours(1), Duration.ofHours(1), System::nanoTime, 10, 10, 10)) {

            // when/then
            assertThat(processor.getScannerState()).isEqualTo(SpanTimeoutScannerState.DISABLED);
            assertThat(processor.getLastSuccessfulBackgroundScanEpochMillis()).isZero();
        }
    }

    @Test
    void backgroundScannerRunsAndReportsHeartbeatThenStopsOnShutdown() {
        // given
        try (SpanTimeoutProcessor processor = started(SCAN_INTERVAL, System::nanoTime)) {

            // when
            assertThat(processor.getScannerState()).isEqualTo(SpanTimeoutScannerState.RUNNING);
            awaitCondition(() -> processor.getLastSuccessfulBackgroundScanEpochMillis() == FIXED_EPOCH_MILLIS);

            // then
            assertThat(processor.getLastSuccessfulBackgroundScanEpochMillis()).isEqualTo(FIXED_EPOCH_MILLIS);
            assertThat(processor.getScanFailuresTotal()).isZero();

            assertThat(processor.shutdown().isSuccess()).isTrue();
            assertThat(processor.getScannerState()).isEqualTo(SpanTimeoutScannerState.STOPPED);
        }
    }

    @Test
    void recoverableErrorFromTheClockDoesNotStopTheBackgroundScanner() {
        // given
        AtomicInteger failuresLeft = new AtomicInteger(1);
        LongSupplier nanoTime = () -> {
            if (failuresLeft.getAndDecrement() > 0) {
                throw new AssertionError("injected recoverable clock failure");
            }

            return System.nanoTime();
        };

        try (SpanTimeoutProcessor processor = started(SCAN_INTERVAL, nanoTime)) {

            // when
            awaitCondition(() -> (processor.getScanFailuresTotal() == 1L)
                    && (processor.getLastSuccessfulBackgroundScanEpochMillis() == FIXED_EPOCH_MILLIS));

            // then
            assertThat(processor.getScanFailuresTotal()).isEqualTo(1);
            assertThat(processor.getScannerState()).isEqualTo(SpanTimeoutScannerState.RUNNING);
        }
    }

    @Test
    void manualForceFlushDoesNotAdvanceTheBackgroundHeartbeat() {
        // given
        try (SpanTimeoutProcessor processor = started(Duration.ofDays(1), System::nanoTime)) {

            // when
            assertThat(processor.forceFlush().isSuccess()).isTrue();

            // then
            assertThat(processor.getLastSuccessfulBackgroundScanEpochMillis()).isZero();
            assertThat(processor.getScannerState()).isEqualTo(SpanTimeoutScannerState.RUNNING);
        }
    }

    @Test
    void fatalErrorTerminatesTheBackgroundScannerAbnormallyAndShutdownReportsIt() {
        // given
        try (AgentLogCapture logs = AgentLogCapture.installed()) {
            LongSupplier nanoTime = () -> {
                throw new LinkageError("injected fatal clock failure");
            };

            try (SpanTimeoutProcessor processor = started(SCAN_INTERVAL, nanoTime)) {

                // when
                awaitCondition(() -> processor.getScannerState() == SpanTimeoutScannerState.TERMINATED_ABNORMALLY);

                // then
                assertThat(processor.getScannerState()).isEqualTo(SpanTimeoutScannerState.TERMINATED_ABNORMALLY);
                assertThat(processor.getLastSuccessfulBackgroundScanEpochMillis()).isZero();
                assertThat(processor.getScanFailuresTotal()).isEqualTo(1);

                assertThat(processor.shutdown().isSuccess()).isTrue();
                assertThat(processor.getScannerState()).isEqualTo(SpanTimeoutScannerState.TERMINATED_ABNORMALLY);

                assertThat(logs.events())
                        .filteredOn(event -> event.level() == AgentLogLevel.WARN)
                        .extracting(CapturedLogEvent::message)
                        .contains(
                                "Span-timeout scanner terminated abnormally",
                                "Span-timeout scanner had terminated abnormally before shutdown"
                        );

                assertThat(logs.events())
                        .filteredOn(event -> "Span-timeout scanner terminated abnormally".equals(event.message()))
                        .extracting(CapturedLogEvent::throwableDigest)
                        .singleElement()
                        .asString()
                        .contains("LinkageError");

                assertThat(logs.events())
                        .filteredOn(event -> "Span-timeout scanner had terminated abnormally before shutdown".equals(event.message()))
                        .extracting(CapturedLogEvent::throwableDigest)
                        .singleElement()
                        .isNull();

                assertThat(logs.events())
                        .filteredOn(event -> event.level() == AgentLogLevel.WARN)
                        .extracting(CapturedLogEvent::eventCode)
                        .containsOnly(AgentEventCode.EXPORT_SPAN_TIMEOUT);
            }
        }
    }

    private static void awaitCondition(BooleanSupplier condition) {
        long deadlineNanos = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (condition.getAsBoolean()) {
                return;
            }

            sleep();
        }
    }

    private static void sleep() {
        try {
            TimeUnit.MILLISECONDS.sleep(5);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
