package space.br1440.platform.tracing.otel.javaagent.safety;

import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт {@link RateLimitedLogger}: двухфазный throttling (без log storm) и recovery-once.
 * Используем {@link NOPLogger} как делегат — проверяем булев результат {@code warn(...)}
 * (записано/подавлено), не разбирая фактический вывод.
 */
class RateLimitedLoggerTest {

    @Test
    void двухфазный_throttling_подавляет_лишние_сообщения() {
        AtomicLong clock = new AtomicLong(0);
        // fast=2/мин, throttled=1/мин, время не идёт.
        RateLimitedLogger logger = new RateLimitedLogger(
                NOPLogger.NOP_LOGGER, 2, 1, 1, TimeUnit.MINUTES, clock::get);

        assertThat(logger.warn("msg")).as("fast #1").isTrue();
        assertThat(logger.warn("msg")).as("fast #2").isTrue();
        assertThat(logger.isThrottled()).isFalse();

        assertThat(logger.warn("msg")).as("переход в throttled + сообщение").isTrue();
        assertThat(logger.isThrottled()).isTrue();

        assertThat(logger.warn("msg")).as("throttled #1 (последний токен)").isTrue();
        assertThat(logger.warn("msg")).as("throttled исчерпан — подавлено").isFalse();
    }

    @Test
    void recovery_once_срабатывает_однократно_после_проблемы() {
        RateLimitedLogger logger = new RateLimitedLogger(NOPLogger.NOP_LOGGER);

        // Пока проблем не было — recovery не пишется.
        assertThat(logger.logRecoveryOnce("recovered")).isFalse();

        logger.warn("проблема");
        assertThat(logger.logRecoveryOnce("recovered")).as("первое восстановление").isTrue();
        assertThat(logger.logRecoveryOnce("recovered")).as("повторно — нет").isFalse();
    }

    @Test
    void runtime_log_level_приглушает_warn_при_уровне_ниже_WARN() {
        AtomicLong clock = new AtomicLong(0);
        PlatformLogControl control = new PlatformLogControl();
        RateLimitedLogger logger = new RateLimitedLogger(
                NOPLogger.NOP_LOGGER, 5, 1, 1, TimeUnit.MINUTES, clock::get, control);

        control.setLevel(PlatformLogControl.LogLevel.ERROR);
        assertThat(logger.warn("msg")).as("ERROR < WARN — подавлено гейтом").isFalse();

        control.setLevel(PlatformLogControl.LogLevel.OFF);
        assertThat(logger.warn("msg")).as("OFF — подавлено").isFalse();

        control.setLevel(PlatformLogControl.LogLevel.WARN);
        assertThat(logger.warn("msg")).as("WARN — снова печатается").isTrue();
    }
}
