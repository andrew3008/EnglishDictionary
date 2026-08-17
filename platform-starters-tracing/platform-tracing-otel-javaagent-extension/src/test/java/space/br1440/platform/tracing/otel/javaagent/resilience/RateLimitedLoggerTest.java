package space.br1440.platform.tracing.otel.javaagent.resilience;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.diagnostics.PlatformLogControl;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.logging.EmitResult;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт {@link RateLimitedLogger}: двухфазный throttling и recovery-once.
 */
class RateLimitedLoggerTest {

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
    void two_phase_throttling_suppresses_extra_messages() {
        // given
        AtomicLong clock = new AtomicLong(0);
        RateLimitedLogger logger = new RateLimitedLogger(
                PlatformAgentLogger.get(RateLimitedLoggerTest.class),
                2, 1, 1, TimeUnit.MINUTES, clock::get);

        // when
        // then
        assertThat(logger.warn(AgentEventCodes.EXPORT_SAFE_EXPORTER, "msg"))
                .as("fast #1").isEqualTo(EmitResult.EMITTED);
        assertThat(logger.warn(AgentEventCodes.EXPORT_SAFE_EXPORTER, "msg"))
                .as("fast #2").isEqualTo(EmitResult.EMITTED);
        assertThat(logger.isThrottled()).isFalse();

        assertThat(logger.warn(AgentEventCodes.EXPORT_SAFE_EXPORTER, "msg"))
                .as("переход в throttled + сообщение").isEqualTo(EmitResult.EMITTED);
        assertThat(logger.isThrottled()).isTrue();

        assertThat(logger.warn(AgentEventCodes.EXPORT_SAFE_EXPORTER, "msg"))
                .as("throttled #1 (последний токен)").isEqualTo(EmitResult.EMITTED);
        assertThat(logger.warn(AgentEventCodes.EXPORT_SAFE_EXPORTER, "msg"))
                .as("throttled исчерпан — подавлено").isEqualTo(EmitResult.RATE_LIMITED);
    }

    @Test
    void recovery_once_fires_once_after_problem() {
        // given
        RateLimitedLogger logger = new RateLimitedLogger(RateLimitedLoggerTest.class);

        // when
        // then
        assertThat(logger.logRecoveryOnce("recovered")).isEqualTo(EmitResult.FILTERED_BY_LEVEL);

        logger.warn(AgentEventCodes.EXPORT_SAFE_EXPORTER, "problem");
        assertThat(logger.logRecoveryOnce("recovered")).as("первое восстановление")
                .isEqualTo(EmitResult.EMITTED);
        assertThat(logger.logRecoveryOnce("recovered")).as("повторно — нет")
                .isEqualTo(EmitResult.FILTERED_BY_LEVEL);
    }

    @Test
    void runtime_log_level_suppresses_warn_when_level_below_WARN() {
        // given
        AtomicLong clock = new AtomicLong(0);
        RateLimitedLogger logger = new RateLimitedLogger(
                PlatformAgentLogger.get(RateLimitedLoggerTest.class),
                5, 1, 1, TimeUnit.MINUTES, clock::get);
        PlatformLogControl.shared().setLevel(AgentLogLevel.ERROR);

        // when
        // then
        assertThat(logger.warn(AgentEventCodes.EXPORT_SAFE_EXPORTER, "msg"))
                .as("ERROR < WARN — подавлено гейтом").isEqualTo(EmitResult.FILTERED_BY_LEVEL);

        PlatformLogControl.shared().setLevel(AgentLogLevel.OFF);
        assertThat(logger.warn(AgentEventCodes.EXPORT_SAFE_EXPORTER, "msg"))
                .as("OFF — подавлено").isEqualTo(EmitResult.FILTERED_BY_LEVEL);

        PlatformLogControl.shared().setLevel(AgentLogLevel.WARN);
        assertThat(logger.warn(AgentEventCodes.EXPORT_SAFE_EXPORTER, "msg"))
                .as("WARN — снова печатается").isEqualTo(EmitResult.EMITTED);
    }
}
