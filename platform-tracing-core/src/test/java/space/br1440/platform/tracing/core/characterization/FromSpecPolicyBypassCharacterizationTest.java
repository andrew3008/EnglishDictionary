package space.br1440.platform.tracing.core.characterization;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.semconv.SemconvValidationMode;
import space.br1440.platform.tracing.api.semconv.SemconvViolationException;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.core.manual.DefaultSpanFactory;
import space.br1440.platform.tracing.core.runtime.RecordingTracingRuntime;
import space.br1440.platform.tracing.core.runtime.otel.SpanSpecAttributeValueConverter;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;
import space.br1440.platform.tracing.core.semconv.policy.SemconvMetrics;
import space.br1440.platform.tracing.test.characterization.KnownDefect;
import space.br1440.platform.tracing.test.characterization.KnownDefectId;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class FromSpecPolicyBypassCharacterizationTest {

    @Test
    @KnownDefect(KnownDefectId.FROM_SPEC_POLICY_BYPASS)
    void fromSpecBypassesStrictAttributePolicy() {
        AttributePolicy strictPolicy = new AttributePolicy(
                SemconvValidationMode.STRICT, false, SemconvMetrics.NOOP);
        SpanSpec invalidSpec = SpanSpec.builder("characterization.from-spec")
                .category(SpanCategory.INTERNAL)
                .reason(SpanSpecReason.LEGACY_INTEGRATION)
                .build();

        assertThatExceptionOfType(SemconvViolationException.class)
                .isThrownBy(() -> strictPolicy.validateAndNormalize(
                        invalidSpec.category(),
                        SpanSpecAttributeValueConverter.toAttributes(invalidSpec.attributes()),
                        "characterization"));

        DefaultSpanFactory factory = new DefaultSpanFactory(new RecordingTracingRuntime(), strictPolicy);
        assertThatCode(() -> factory.fromSpec(invalidSpec).start().close())
                .doesNotThrowAnyException();
    }
}
