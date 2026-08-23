package space.br1440.platform.tracing.autoconfigure.integrations.observation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class HttpObservationSuppressionStartupTest {

    private Logger runnerLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        appender = new ListAppender<>();
        appender.start();

        runnerLogger = (Logger) LoggerFactory.getLogger(HttpObservationSuppressionStartupRunner.class);
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
        // given
        // when
        new HttpObservationSuppressionStartupRunner(true, false).afterSingletonsInstantiated();

        // then
        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getLevel()).isEqualTo(Level.WARN);
        assertThat(events.getFirst().getFormattedMessage())
                .contains("was not detected")
                .contains("http.server.requests")
                .doesNotContain("HTTP spans may be duplicated");
    }

    @Test
    void warnWhenSuppressFalseAndAgentDetected() {
        // given
        // when
        new HttpObservationSuppressionStartupRunner(false, true).afterSingletonsInstantiated();

        // then
        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getLevel()).isEqualTo(Level.WARN);
        assertThat(events.getFirst().getFormattedMessage())
                .contains("OpenTelemetry Java Agent detected")
                .contains("Micrometer HTTP observations remain enabled")
                .contains("does not add a second HTTP span producer")
                .doesNotContain("HTTP spans may be duplicated");
    }

    @Test
    void noWarnWhenSuppressTrueAndAgentDetected() {
        // suppress=true, agent=yes → штатный сценарий, WARN не ожидается.
        new HttpObservationSuppressionStartupRunner(true, true).afterSingletonsInstantiated();

        assertThat(appender.list).isEmpty();
    }

    @Test
    void noWarnWhenSuppressFalseAndAgentNotDetected() {
        // suppress=false, agent=no → default-сценарий, WARN не ожидается.
        new HttpObservationSuppressionStartupRunner(false, false).afterSingletonsInstantiated();

        assertThat(appender.list).isEmpty();
    }
}
