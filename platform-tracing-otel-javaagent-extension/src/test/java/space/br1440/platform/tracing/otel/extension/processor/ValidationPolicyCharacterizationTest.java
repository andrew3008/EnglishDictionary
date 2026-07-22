package space.br1440.platform.tracing.otel.extension.processor;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.extension.exception.TracingValidationException;
import space.br1440.platform.tracing.test.assertions.ValidationAssertions;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;
import space.br1440.platform.tracing.test.harness.ValidationDecisionCase;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Characterization validation policy modes (PR-5B).
 */
class ValidationPolicyCharacterizationTest {

    static Stream<ValidationDecisionCase> characterizedCases() {
        return Stream.of(
                new ValidationDecisionCase(
                        "VAL-LEN-MISS", true, false, false, false, true, true, null),
                new ValidationDecisionCase(
                        "VAL-LEN-OK", true, false, true, true, true, false, null),
                new ValidationDecisionCase(
                        "VAL-STRICT-MISS", true, true, false, false, false, false,
                        TracingValidationException.class),
                new ValidationDecisionCase(
                        "VAL-DISABLED", false, false, false, false, true, false, null)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("characterizedCases")
    void matrix_case(ValidationDecisionCase c) {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(c.strict());
        processor.updateValidationPolicy(c.enabled(), c.strict());

        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor, Resource.empty())) {
            Tracer tracer = h.tracer("validation-characterization");
            var builder = tracer.spanBuilder("op");
            if (c.hasPlatformType()) {
                builder.setAttribute(PlatformAttributes.PLATFORM_TYPE, "HTTP");
            }
            if (c.hasPlatformResult()) {
                builder.setAttribute(PlatformAttributes.PLATFORM_RESULT, "success");
            }

            if (c.expectedThrowable() != null) {
                assertThatThrownBy(() -> builder.startSpan().end())
                        .isInstanceOf(c.expectedThrowable());
                return;
            }

            builder.startSpan().end();
            var data = h.exporter().getFinishedSpanItems().get(0);

            if (c.expectMissingAttribute()) {
                ValidationAssertions.assertMissingRecorded(
                        data, PlatformAttributes.PLATFORM_TYPE, PlatformAttributes.PLATFORM_RESULT);
            } else {
                ValidationAssertions.assertMissingAbsent(data);
            }
            if (c.hasPlatformType() && c.hasPlatformResult()) {
                ValidationAssertions.assertPlatformAttributesPresent(data);
            }
        }
    }
}
