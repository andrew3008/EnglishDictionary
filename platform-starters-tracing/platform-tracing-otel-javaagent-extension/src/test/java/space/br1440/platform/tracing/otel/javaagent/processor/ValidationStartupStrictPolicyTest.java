package space.br1440.platform.tracing.otel.javaagent.processor;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет безопасную диагностическую семантику строгого режима.
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
        processorLogger.setLevel(Level.ALL);
    }

    @AfterEach
    void tearDown() {
        processorLogger.detachAppender(appender);
    }

    @Test
    void strict_violation_emits_sanitized_error() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(TracingSettingsSource.isolatedValidation(true, true, false));
        try (SpanProcessorHarness harness = SpanProcessorHarness.of(processor, Resource.empty())) {
            harness.tracer("test").spanBuilder("customer-secret-span-name").startSpan().end();
        }

        assertThat(appender.list)
                .filteredOn(e -> e.getLevel() == Level.ERROR)
                .hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .isEqualTo("PT-VALIDATION-MISSING-ATTRIBUTES: span violates the mandatory attribute contract")
                .doesNotContain("customer-secret-span-name", "platform.trace.type", "platform.trace.result");
    }

    @Test
    void lenient_violation_emits_warn_instead_of_error() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(TracingSettingsSource.isolatedValidation(true, false, false));
        try (SpanProcessorHarness harness = SpanProcessorHarness.of(processor, Resource.empty())) {
            harness.tracer("test").spanBuilder("missing-attributes").startSpan().end();
        }

        assertThat(appender.list)
                .filteredOn(e -> e.getLevel() == Level.WARN)
                .hasSize(1);
        assertThat(appender.list)
                .filteredOn(e -> e.getLevel() == Level.ERROR)
                .isEmpty();
    }

    @Test
    void startup_strict_true_preserves_strict_behavior() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(TracingSettingsSource.isolatedValidation(true, true, false));

        assertThat(processor.isStrict()).isTrue();
    }
}
