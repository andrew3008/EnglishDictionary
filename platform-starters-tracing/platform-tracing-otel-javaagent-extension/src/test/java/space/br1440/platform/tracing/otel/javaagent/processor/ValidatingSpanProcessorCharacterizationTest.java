package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.test.assertions.ValidationAssertions;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;

class ValidatingSpanProcessorCharacterizationTest {

    @Test
    void runtime_policy_update_disables_subsequent_validation() {
        TracingSettingsSource access = TracingSettingsSource.isolatedValidation(false, false);
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(access);

        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor, Resource.empty())) {
            Tracer tracer = h.tracer("t");
            tracer.spanBuilder("op").startSpan().end();

            ValidationAssertions.assertMissingAbsent(h.exporter().getFinishedSpanItems().getFirst());
        }
    }

    @Test
    void warn_level_records_missing_but_export_continues() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(
                TracingSettingsSource.isolatedValidation(true, false)
        );

        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor, Resource.empty())) {
            Tracer tracer = h.tracer("t");
            tracer.spanBuilder("op").startSpan().end();

            var data = h.exporter().getFinishedSpanItems().getFirst();
            ValidationAssertions.assertMissingRecorded(
                    data,
                    PlatformAttributes.PLATFORM_TYPE,
                    PlatformAttributes.PLATFORM_RESULT
            );
            assertThat(data.getName()).isEqualTo("op");
        }
    }
}
