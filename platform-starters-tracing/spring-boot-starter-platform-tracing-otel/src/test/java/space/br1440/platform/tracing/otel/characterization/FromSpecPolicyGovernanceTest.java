package space.br1440.platform.tracing.otel.characterization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import space.br1440.platform.tracing.api.attributes.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.api.attributes.validation.SpanAttributeViolationException;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecAttributeValue;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidationMetrics;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidator;
import space.br1440.platform.tracing.otel.runtime.RecordingTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.otel.SpanSpecAttributeValueConverter;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;
import space.br1440.platform.tracing.otel.span.DefaultSpanFactory;

class FromSpecPolicyGovernanceTest {

    @Test
    void fromSpecRejectsMissingReason() {
        // given
        RecordingTracingRuntime runtime = new RecordingTracingRuntime();
        DefaultSpanFactory factory = new DefaultSpanFactory(runtime, new SpanAttributeValidator());
        SpanSpec spec = SpanSpec.builder("characterization.missing-reason")
                .category(SpanCategory.INTERNAL)
                .build();

        // when
        // then
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> factory.fromSpec(spec))
                .withMessage("fromSpec requires reason(...)");
    }

    @Test
    void fromSpecRejectsAttributesRejectedByErrorPolicy() {
        SpanAttributeValidator errorPolicy = new SpanAttributeValidator(
                SpanAttributeValidationLevel.ERROR,
                SpanAttributeValidationMetrics.NOOP
        );

        SpanSpec invalidSpec = SpanSpec.builder("characterization.from-spec")
                .category(SpanCategory.INTERNAL)
                .reason(SpanSpecReason.LEGACY_INTEGRATION)
                .build();

        assertThatExceptionOfType(SpanAttributeViolationException.class)
                .isThrownBy(() -> errorPolicy.validateAndNormalize(
                        invalidSpec.category(),
                        SpanSpecAttributeValueConverter.toAttributes(invalidSpec.attributes()),
                        "characterization"));

        RecordingTracingRuntime runtime = new RecordingTracingRuntime();
        DefaultSpanFactory factory = new DefaultSpanFactory(runtime, errorPolicy);
        assertThatExceptionOfType(SpanAttributeViolationException.class)
                .isThrownBy(() -> {
                    try (SpanHandle handle = factory.fromSpec(invalidSpec).start()) {
                        throw new AssertionError("ERROR fromSpec().start() must not return " + handle);
                    }
                });
        assertThat(runtime.receivedSpecs()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(SpanCategory.class)
    void fromSpecPassesNormalizedAttributesToRuntime(SpanCategory category) {
        RemoteSpanLink link = RemoteSpanLink.sampled(
                "01234567890123456789012345678901",
                "0123456789012345"
        );

        RecordingTracingRuntime runtime = new RecordingTracingRuntime();
        SpanAttributeValidator warnPolicy = new SpanAttributeValidator(
                SpanAttributeValidationLevel.WARNING,
                SpanAttributeValidationMetrics.NOOP
        );

        DefaultSpanFactory factory = new DefaultSpanFactory(runtime, warnPolicy);
        SpanSpec spec = SpanSpec.builder("characterization.normalized-from-spec")
                .category(category)
                .root()
                .linkedTo(link)
                .reason(SpanSpecReason.LEGACY_INTEGRATION)
                .reference("ALIGN-04")
                .build();

        try (SpanHandle handle = factory.fromSpec(spec).start()) {
            assertThat(handle).isNotNull();
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
}
