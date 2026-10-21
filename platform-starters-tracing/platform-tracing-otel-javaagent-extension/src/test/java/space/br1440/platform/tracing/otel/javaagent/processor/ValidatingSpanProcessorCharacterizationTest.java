package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.test.assertions.ValidationAssertions;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.SpanData;

class ValidatingSpanProcessorCharacterizationTest {

    @Test
    void runtime_policy_update_disables_subsequent_validation() {
        // given
        TracingSettingsSource access = TracingSettingsSourceTestFixtures.boundValidation(AgentInstanceId.random(), SpanAttributeValidationLevel.DISABLED);
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(access);

        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor, Resource.empty())) {
            Tracer tracer = h.tracer("t");
            // when
            tracer.spanBuilder("op").startSpan().end();

            ValidationAssertions.assertMissingAbsent(h.exporter().getFinishedSpanItems().getFirst());
        }
    }

    @Test
    void warn_level_records_missing_but_export_continues() {
        // given
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(
                TracingSettingsSourceTestFixtures.boundValidation(
                        AgentInstanceId.random(),
                        SpanAttributeValidationLevel.WARNING
                )
        );

        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor, Resource.empty())) {
            Tracer tracer = h.tracer("t");
            // when
            tracer.spanBuilder("op").startSpan().end();

            SpanData data = h.exporter().getFinishedSpanItems().getFirst();
            ValidationAssertions.assertMissingRecorded(
                    data,
                    PlatformAttributes.PLATFORM_TYPE,
                    PlatformAttributes.PLATFORM_RESULT
            );
            // then
            assertThat(data.getName()).isEqualTo("op");
        }
    }
}
