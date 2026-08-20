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

import reactor.core.publisher.Hooks;

import static org.assertj.core.api.Assertions.assertThat;

class TracingReactorContextPropagationInitializerTest {

    private Logger initializerLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        initializerLogger = (Logger) LoggerFactory.getLogger(TracingReactorContextPropagationInitializer.class);
        appender = new ListAppender<>();
        appender.start();
        initializerLogger.addAppender(appender);
        initializerLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        initializerLogger.detachAppender(appender);
        appender.stop();
        Hooks.disableAutomaticContextPropagation();
    }

    @Test
    void enablesAutomaticPropagationWhenPropertyIsAbsent() {
        MockEnvironment environment = new MockEnvironment();

        new TracingReactorContextPropagationInitializer(environment).afterSingletonsInstantiated();

        assertThat(Hooks.isAutomaticContextPropagationEnabled()).isTrue();
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    void enablesAutomaticPropagationWhenPropertyIsAuto() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.reactor.context-propagation", "AUTO");

        new TracingReactorContextPropagationInitializer(environment).afterSingletonsInstantiated();

        assertThat(Hooks.isAutomaticContextPropagationEnabled()).isTrue();
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    void respectsExplicitLimitedChoiceAndWarns() {
        Hooks.disableAutomaticContextPropagation();
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.reactor.context-propagation", "LIMITED");

        new TracingReactorContextPropagationInitializer(environment).afterSingletonsInstantiated();

        assertThat(Hooks.isAutomaticContextPropagationEnabled()).isFalse();
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("spring.reactor.context-propagation")
                .contains("LIMITED");
    }
}
