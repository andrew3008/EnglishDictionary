package space.br1440.platform.tracing.otel.javaagent.processor;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.test.assertions.ValidationAssertions;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;
import space.br1440.platform.tracing.test.harness.ValidationDecisionCase;

import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.SpanData;

class ValidationPolicyCharacterizationTest {

    static Stream<ValidationDecisionCase> characterizedCases() {
        return Stream.of(
                new ValidationDecisionCase("VAL-WARNING-MISS", "WARNING", false, false, true, true, null),

                new ValidationDecisionCase("VAL-WARNING-OK", "WARNING", true, true, true, false, null),

                new ValidationDecisionCase("VAL-ERROR-MISS", "ERROR", false, false, true, true, null),

                new ValidationDecisionCase("VAL-DISABLED", "DISABLED", false, false, true, false, null)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("characterizedCases")
    void matrix_case(ValidationDecisionCase c) {
        // given
        TracingSettingsSource access = TracingSettingsSourceTestFixtures.boundValidation(AgentInstanceId.random(),
                SpanAttributeValidationLevel.valueOf(c.validationLevel())
        );

        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(access);

        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor, Resource.empty())) {
            Tracer tracer = h.tracer("validation-characterization");
            SpanBuilder builder = tracer.spanBuilder("op");
            if (c.hasPlatformType()) {
                builder.setAttribute(PlatformAttributes.PLATFORM_TYPE, "HTTP");
            }

            if (c.hasPlatformResult()) {
                builder.setAttribute(PlatformAttributes.PLATFORM_RESULT, "success");
            }

            // when
            builder.startSpan().end();
            SpanData data = h.exporter().getFinishedSpanItems().getFirst();

            if (c.expectMissingAttribute()) {
                ValidationAssertions.assertMissingRecorded(
                        data, PlatformAttributes.PLATFORM_TYPE, PlatformAttributes.PLATFORM_RESULT
                );
            } else {
                ValidationAssertions.assertMissingAbsent(data);
            }

            if (c.hasPlatformType() && c.hasPlatformResult()) {
                ValidationAssertions.assertPlatformAttributesPresent(data);
            }
        }
    }
}
