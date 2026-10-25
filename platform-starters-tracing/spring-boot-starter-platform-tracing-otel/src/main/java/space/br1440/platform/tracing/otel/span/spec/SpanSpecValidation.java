package space.br1440.platform.tracing.otel.span.spec;

import java.util.Map;
import java.util.Objects;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecAttributeValue;
import space.br1440.platform.tracing.api.span.spec.SpanSpecBuilder;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidator;
import space.br1440.platform.tracing.otel.attributes.validation.ValidatedAttributes;
import space.br1440.platform.tracing.otel.runtime.otel.SpanSpecAttributeValueConverter;

import io.opentelemetry.api.common.Attributes;
import lombok.experimental.UtilityClass;

@UtilityClass
final class SpanSpecValidation {

    @Nonnull
    static SpanSpec validateAndNormalize(@Nonnull SpanSpec spec,
                                         @Nonnull SpanAttributeValidator policy,
                                         @Nonnull String builderName) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(builderName, "builderName");

        Attributes accumulated = SpanSpecAttributeValueConverter.toAttributes(spec.attributes());
        ValidatedAttributes validated = policy.validateAndNormalize(spec.category(), accumulated, builderName);
        Map<String, SpanSpecAttributeValue> normalized = SpanSpecAttributeValueConverter.fromAttributes(validated.attributes());
        if (normalized.equals(spec.attributes())) {
            return spec;
        }

        SpanSpecBuilder builder = SpanSpec.builder(spec.name()).category(spec.category());
        spec.reason().ifPresent(builder::reason);
        SpanSpecBuilderSupport.applyRelationship(
                builder,
                spec.relationship().kind(),
                spec.relationship().links()
        );
        SpanSpecBuilderSupport.applyAttributes(builder, normalized);
        spec.reference().ifPresent(builder::reference);
        return builder.build();
    }
}
