package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import io.opentelemetry.sdk.resources.Resource;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class ValidationStartupErrorPolicyTest {

    @RegisterExtension
    AgentLogCapture capture = new AgentLogCapture();

    @Test
    void error_violation_emits_sanitized_error() {
        // given
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(TracingSettingsSourceTestFixtures.boundValidation(AgentInstanceId.random(), SpanAttributeValidationLevel.ERROR));

        // when
        try (SpanProcessorHarness harness = SpanProcessorHarness.of(processor, Resource.empty())) {
            harness.tracer("test").spanBuilder("customer-secret-span-name").startSpan().end();
        }

        // then
        assertThat(capture.events())
                .filteredOn(event -> event.level() == AgentLogLevel.ERROR)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.eventCode()).isEqualTo(AgentEventCode.VALID_MISSING_ATTRIBUTES);
                    assertThat(event.message()).isEqualTo("Required span attributes are missing");
                    assertThat(event.fields())
                            .containsEntry("count", "2")
                            .containsEntry("missingKeys", PlatformAttributes.PLATFORM_TYPE + "," + PlatformAttributes.PLATFORM_RESULT);
                    assertThat(event.fields().toString()).doesNotContain("customer-secret-span-name");
                });
    }

    @Test
    void warning_level_violation_emits_warn_instead_of_error() {
        // given
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(TracingSettingsSourceTestFixtures.boundValidation(AgentInstanceId.random(), SpanAttributeValidationLevel.WARNING));

        // when
        try (SpanProcessorHarness harness = SpanProcessorHarness.of(processor, Resource.empty())) {
            harness.tracer("test").spanBuilder("missing-attributes").startSpan().end();
        }

        // then
        assertThat(capture.events())
                .filteredOn(event -> event.level() == AgentLogLevel.WARN)
                .hasSize(1);
        assertThat(capture.events())
                .filteredOn(event -> event.level() == AgentLogLevel.ERROR)
                .isEmpty();
    }

    @Test
    void startup_error_preserves_error_behavior() {
        // given/when
        try (ValidatingSpanProcessor processor = new ValidatingSpanProcessor(TracingSettingsSourceTestFixtures.boundValidation(AgentInstanceId.random(), SpanAttributeValidationLevel.ERROR))) {
            // then
            assertThat(processor.validationLevel()).isEqualTo(SpanAttributeValidationLevel.ERROR);
        }
    }
}
