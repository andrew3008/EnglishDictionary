package space.br1440.platform.tracing.otel.extension.processor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-9J: startup strict-mode ops policy (W-013) — one-time WARN at processor construction.
 */
class ValidationStartupStrictPolicyTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger processorLogger;

    @BeforeEach
    void setUp() {
        appender = new ListAppender<>();
        appender.start();
        processorLogger = (Logger) LoggerFactory.getLogger(ValidatingSpanProcessor.class);
        processorLogger.addAppender(appender);
        processorLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void tearDown() {
        processorLogger.detachAppender(appender);
    }

    @Test
    void startup_strict_true_emits_single_warn() {
        new ValidatingSpanProcessor(true);

        assertThat(appender.list)
                .filteredOn(e -> e.getLevel() == Level.WARN)
                .hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("Validation strict mode is enabled at startup")
                .contains("CI/test/pre-prod diagnostics")
                .contains("Span.end()");
    }

    @Test
    void startup_strict_false_does_not_emit_warn() {
        new ValidatingSpanProcessor(false);

        assertThat(appender.list)
                .filteredOn(e -> e.getLevel() == Level.WARN)
                .isEmpty();
    }

    @Test
    void startup_strict_true_preserves_strict_behavior() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(true);

        assertThat(processor.isStrict()).isTrue();
    }
}
