package space.br1440.platform.tracing.otel.characterization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import space.br1440.platform.tracing.api.semconv.SemconvValidationMode;
import space.br1440.platform.tracing.api.semconv.SemconvViolationException;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecAttributeValue;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.otel.manual.DefaultSpanFactory;
import space.br1440.platform.tracing.otel.runtime.RecordingTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.otel.SpanSpecAttributeValueConverter;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;
import space.br1440.platform.tracing.otel.semconv.policy.AttributePolicy;
import space.br1440.platform.tracing.otel.semconv.policy.SemconvMetrics;

class FromSpecPolicyGovernanceTest {

    @Test
    void fromSpecRejectsAttributesRejectedByStrictPolicy() {
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

        RecordingTracingRuntime runtime = new RecordingTracingRuntime();
        DefaultSpanFactory factory = new DefaultSpanFactory(runtime, strictPolicy);
        assertThatExceptionOfType(SemconvViolationException.class)
                .isThrownBy(() -> factory.fromSpec(invalidSpec).start());
        assertThat(runtime.receivedSpecs()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(SpanCategory.class)
    void fromSpecPassesNormalizedAttributesToRuntime(SpanCategory category) {
        RemoteSpanLink link = RemoteSpanLink.sampled(
                "01234567890123456789012345678901",
                "0123456789012345");
        RecordingTracingRuntime runtime = new RecordingTracingRuntime();
        AttributePolicy warnPolicy = new AttributePolicy(
                SemconvValidationMode.WARN, false, SemconvMetrics.NOOP);
        DefaultSpanFactory factory = new DefaultSpanFactory(runtime, warnPolicy);
        SpanSpec spec = SpanSpec.builder("characterization.normalized-from-spec")
                .category(category)
                .root()
                .linkedTo(link)
                .reason(SpanSpecReason.LEGACY_INTEGRATION)
                .reference("ALIGN-04")
                .build();

        factory.fromSpec(spec).start().close();

        assertThat(runtime.receivedSpecs()).singleElement().satisfies(received -> {
            assertThat(received.name()).isEqualTo(spec.name());
            assertThat(received.category()).isEqualTo(spec.category());
            assertThat(received.relationship().kind()).isEqualTo(spec.relationship().kind());
            assertThat(received.relationship().links()).containsExactly(link);
            assertThat(received.reason()).isEqualTo(spec.reason());
            assertThat(received.reference()).isEqualTo(spec.reference());
            assertThat(received.attributes())
                    .containsEntry(SemconvKeys.PLATFORM_TYPE.getKey(),
                            SpanSpecAttributeValue.of(category.value()));
        });
    }
}
