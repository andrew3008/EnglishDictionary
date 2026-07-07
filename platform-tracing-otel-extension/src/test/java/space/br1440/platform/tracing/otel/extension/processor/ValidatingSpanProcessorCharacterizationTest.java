package space.br1440.platform.tracing.otel.extension.processor;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.test.assertions.ValidationAssertions;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization {@link ValidatingSpanProcessor} observable behavior (PR-5B).
 */
class ValidatingSpanProcessorCharacterizationTest {

    @Test
    void runtime_policy_update_disables_subsequent_validation() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(false);
        processor.updateValidationPolicy(false, false);

        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor, Resource.empty())) {
            Tracer tracer = h.tracer("t");
            tracer.spanBuilder("op").startSpan().end();

            ValidationAssertions.assertMissingAbsent(h.exporter().getFinishedSpanItems().get(0));
        }
    }

    @Test
    void lenient_mode_records_missing_but_export_continues() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(false);

        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor, Resource.empty())) {
            Tracer tracer = h.tracer("t");
            tracer.spanBuilder("op").startSpan().end();

            var data = h.exporter().getFinishedSpanItems().get(0);
            ValidationAssertions.assertMissingRecorded(
                    data, PlatformAttributes.PLATFORM_TYPE, PlatformAttributes.PLATFORM_RESULT);
            assertThat(data.getName()).isEqualTo("op");
        }
    }
}
