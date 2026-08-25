package space.br1440.platform.tracing.otel.javaagent.processor;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogLevel;

import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет безопасную диагностическую семантику строгого режима.
 */
class ValidationStartupStrictPolicyTest {

    @RegisterExtension
    AgentLogCapture capture = new AgentLogCapture();

    @Test
    void strict_violation_emits_sanitized_error() {
        // given
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(TracingSettingsSource.isolatedValidation(true, true, false));

        // when
        try (SpanProcessorHarness harness = SpanProcessorHarness.of(processor, Resource.empty())) {
            harness.tracer("test").spanBuilder("customer-secret-span-name").startSpan().end();
        }

        // then
        assertThat(capture.events())
                .filteredOn(event -> event.level() == AgentLogLevel.ERROR)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.eventCode()).isEqualTo(AgentEventCodes.VALID_MISSING_ATTRIBUTES);
                    assertThat(event.message()).isEqualTo("Required span attributes are missing");
                    assertThat(event.fields().toString())
                            .doesNotContain("customer-secret-span-name", "platform.trace.type", "platform.trace.result");
                });
    }

    @Test
    void lenient_violation_emits_warn_instead_of_error() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(TracingSettingsSource.isolatedValidation(true, false, false));
        try (SpanProcessorHarness harness = SpanProcessorHarness.of(processor, Resource.empty())) {
            harness.tracer("test").spanBuilder("missing-attributes").startSpan().end();
        }

        assertThat(capture.events())
                .filteredOn(event -> event.level() == AgentLogLevel.WARN)
                .hasSize(1);
        assertThat(capture.events())
                .filteredOn(event -> event.level() == AgentLogLevel.ERROR)
                .isEmpty();
    }

    @Test
    void startup_strict_true_preserves_strict_behavior() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(TracingSettingsSource.isolatedValidation(true, true, false));

        assertThat(processor.isStrict()).isTrue();
    }
}
