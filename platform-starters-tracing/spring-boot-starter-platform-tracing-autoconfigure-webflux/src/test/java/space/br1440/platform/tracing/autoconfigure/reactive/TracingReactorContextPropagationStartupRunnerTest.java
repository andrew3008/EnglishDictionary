package space.br1440.platform.tracing.autoconfigure.reactive;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты {@link TracingReactorContextPropagationStartupRunner}.
 */
class TracingReactorContextPropagationStartupRunnerTest {

    private Logger runnerLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        runnerLogger = (Logger) LoggerFactory.getLogger(TracingReactorContextPropagationStartupRunner.class);
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
    void warnWhenContextPropagationNotAuto() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.reactor.context-propagation", "DISABLED");

        new TracingReactorContextPropagationStartupRunner(environment).afterSingletonsInstantiated();

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("spring.reactor.context-propagation");
    }

    @Test
    void noWarnWhenContextPropagationAuto() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.reactor.context-propagation", "AUTO");

        new TracingReactorContextPropagationStartupRunner(environment).afterSingletonsInstantiated();

        assertThat(appender.list).isEmpty();
    }
}
