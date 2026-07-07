package space.br1440.platform.tracing.autoconfigure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты на матрицу 2×2 поведения {@link TracingObservationSuppressStartupRunner}:
 * пара (suppress включён? × Agent обнаружен?) × WARN-сообщение.
 * <p>
 * Все четыре ячейки матрицы покрываются отдельным {@code @Test}-методом, включая «тихую» ячейку
 * {@code suppress=false × agent=no}, чтобы исключить регрессию случайно-добавленного WARN
 * в default-сценарии.
 */
class TracingObservationSuppressStartupTest {

    private Logger runnerLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        runnerLogger = (Logger) LoggerFactory.getLogger(TracingObservationSuppressStartupRunner.class);
        appender = new ListAppender<>();
        appender.start();
        runnerLogger.addAppender(appender);
        runnerLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void tearDown() {
        runnerLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void warnWhenSuppressTrueAndAgentNotDetected() {
        // suppress=true, agent=no → WARN: «дыра в HTTP-телеметрии».
        new TracingObservationSuppressStartupRunner(true, false).afterSingletonsInstantiated();

        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(events.get(0).getFormattedMessage()).contains("Agent не обнаружен");
    }

    @Test
    void warnWhenSuppressFalseAndAgentDetected() {
        // suppress=false, agent=yes → WARN: «дублирование HTTP-span'ов».
        new TracingObservationSuppressStartupRunner(false, true).afterSingletonsInstantiated();

        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(events.get(0).getFormattedMessage()).contains("Обнаружен OpenTelemetry Java Agent");
    }

    @Test
    void noWarnWhenSuppressTrueAndAgentDetected() {
        // suppress=true, agent=yes → штатный сценарий, WARN не ожидается.
        new TracingObservationSuppressStartupRunner(true, true).afterSingletonsInstantiated();

        assertThat(appender.list).isEmpty();
    }

    @Test
    void noWarnWhenSuppressFalseAndAgentNotDetected() {
        // suppress=false, agent=no → default-сценарий, WARN не ожидается.
        new TracingObservationSuppressStartupRunner(false, false).afterSingletonsInstantiated();

        assertThat(appender.list).isEmpty();
    }
}
