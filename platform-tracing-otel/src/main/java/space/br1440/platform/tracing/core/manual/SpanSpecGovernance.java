package space.br1440.platform.tracing.core.manual;

import java.util.Map;
import java.util.Objects;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.spec.SpanRelationship;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecAttributeValue;
import space.br1440.platform.tracing.api.span.spec.SpanSpecBuilder;
import space.br1440.platform.tracing.core.runtime.otel.SpanSpecAttributeValueConverter;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;

/**
 * Применяет единый semantic-контракт к внешней спецификации span до передачи в runtime.
 */
final class SpanSpecGovernance {

    private SpanSpecGovernance() {
    }

    @Nonnull
    static SpanSpec validateAndNormalize(@Nonnull SpanSpec spec,
                                         @Nonnull AttributePolicy policy,
                                         @Nonnull String builderName) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(builderName, "builderName");

        var accumulated = SpanSpecAttributeValueConverter.toAttributes(spec.attributes());
        var validated = policy.validateAndNormalize(spec.category(), accumulated, builderName);
        Map<String, SpanSpecAttributeValue> normalized =
                SpanSpecAttributeValueConverter.fromAttributes(validated.attributes());

        if (normalized.equals(spec.attributes())) {
            return spec;
        }

        SpanSpecBuilder builder = SpanSpec.builder(spec.name())
                .category(spec.category())
                .reason(spec.reason());
        applyRelationship(builder, spec);
        applyAttributes(builder, normalized);
        spec.reference().ifPresent(builder::reference);
        return builder.build();
    }

    private static void applyRelationship(SpanSpecBuilder builder, SpanSpec spec) {
        SpanRelationship relationship = spec.relationship().kind();
        switch (relationship) {
            case CHILD -> builder.child();
            case ROOT -> {
                builder.root();
                if (!spec.relationship().links().isEmpty()) {
                    builder.linkedTo(spec.relationship().links().toArray(RemoteSpanLink[]::new));
                }
            }
            case DETACHED -> builder.detached();
        }
    }

    private static void applyAttributes(SpanSpecBuilder builder,
                                        Map<String, SpanSpecAttributeValue> attributes) {
        for (Map.Entry<String, SpanSpecAttributeValue> entry : attributes.entrySet()) {
            switch (entry.getValue()) {
                case SpanSpecAttributeValue.StringValue value -> builder.attribute(entry.getKey(), value.value());
                case SpanSpecAttributeValue.LongValue value -> builder.attribute(entry.getKey(), value.value());
                case SpanSpecAttributeValue.DoubleValue value -> builder.attribute(entry.getKey(), value.value());
                case SpanSpecAttributeValue.BooleanValue value -> builder.attribute(entry.getKey(), value.value());
                case SpanSpecAttributeValue.StringListValue value ->
                        builder.stringListAttribute(entry.getKey(), value.values());
                case SpanSpecAttributeValue.LongListValue value ->
                        builder.longListAttribute(entry.getKey(), value.values());
                case SpanSpecAttributeValue.DoubleListValue value ->
                        builder.doubleListAttribute(entry.getKey(), value.values());
                case SpanSpecAttributeValue.BooleanListValue value ->
                        builder.booleanListAttribute(entry.getKey(), value.values());
            }
        }
    }
}
