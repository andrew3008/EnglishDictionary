package space.br1440.platform.tracing.otel.span.spec;

import io.opentelemetry.api.common.Attributes;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.experimental.UtilityClass;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.*;
import space.br1440.platform.tracing.otel.runtime.otel.SpanSpecAttributeValueConverter;
import space.br1440.platform.tracing.otel.semconv.policy.AttributePolicy;
import space.br1440.platform.tracing.otel.semconv.policy.ValidatedAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@UtilityClass
final class SemanticSpanSpecs {

    @Nonnull
    public static SpanSpec build(@Nonnull SpanCategory category,
                                 @Nullable String explicitName,
                                 @Nonnull SpanRelationship relationship,
                                 @Nonnull List<RemoteSpanLink> links,
                                 @Nonnull Map<String, SpanSpecAttributeValue> attributes,
                                 @Nonnull AttributePolicy policy,
                                 @Nonnull String builderName) {

        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(relationship, "relationship");
        Objects.requireNonNull(links, "links");
        Objects.requireNonNull(attributes, "attributes");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(builderName, "builderName");

        Map<String, SpanSpecAttributeValue> enriched = enrichWithPlatformType(attributes, category);
        Attributes accumulated = SpanSpecAttributeValueConverter.toAttributes(enriched);
        ValidatedAttributes validated = policy.validateAndNormalize(category, accumulated, builderName);
        String spanName = PlatformSpanNameBuilder.forCategory(category, validated.attributes(), explicitName);
        Map<String, SpanSpecAttributeValue> normalized = SpanSpecAttributeValueConverter.fromAttributes(validated.attributes());

        return applyAttributes(
                applyRelationship(
                        SpanSpec.builder(spanName)
                                .category(category)
                                .reason(SpanSpecReason.PLATFORM_EDGE_CASE),
                        relationship,
                        links
                ),
                normalized
        ).build();
    }

    private static Map<String, SpanSpecAttributeValue> enrichWithPlatformType(Map<String, SpanSpecAttributeValue> attributes,
                                                                              SpanCategory category) {
        String platformTypeKey = SemconvKeys.PLATFORM_TYPE.getKey();
        if (attributes.containsKey(platformTypeKey)) {
            return attributes;
        }

        Map<String, SpanSpecAttributeValue> enriched = new LinkedHashMap<>(attributes);
        enriched.put(platformTypeKey, SpanSpecAttributeValue.of(category.value()));
        return enriched;
    }

    private static SpanSpecBuilder applyRelationship(SpanSpecBuilder builder,
                                                     SpanRelationship relationship,
                                                     List<RemoteSpanLink> links) {
        return switch (relationship) {
            case CHILD -> builder.child();
            case DETACHED -> builder.detached();
            case ROOT -> links.isEmpty()
                    ? builder.root()
                    : builder.root().linkedTo(links.toArray(RemoteSpanLink[]::new));
        };
    }

    private static SpanSpecBuilder applyAttributes(SpanSpecBuilder builder,
                                                   Map<String, SpanSpecAttributeValue> attributes) {
        for (Map.Entry<String, SpanSpecAttributeValue> entry : attributes.entrySet()) {
            builder = switch (entry.getValue()) {
                case SpanSpecAttributeValue.StringValue sv -> builder.attribute(entry.getKey(), sv.value());
                case SpanSpecAttributeValue.LongValue lv -> builder.attribute(entry.getKey(), lv.value());
                case SpanSpecAttributeValue.DoubleValue dv -> builder.attribute(entry.getKey(), dv.value());
                case SpanSpecAttributeValue.BooleanValue bv -> builder.attribute(entry.getKey(), bv.value());
                case SpanSpecAttributeValue.StringListValue slv -> builder.stringListAttribute(entry.getKey(), slv.values());
                case SpanSpecAttributeValue.LongListValue llv -> builder.longListAttribute(entry.getKey(), llv.values());
                case SpanSpecAttributeValue.DoubleListValue dlv -> builder.doubleListAttribute(entry.getKey(), dlv.values());
                case SpanSpecAttributeValue.BooleanListValue blv -> builder.booleanListAttribute(entry.getKey(), blv.values());
            };
        }

        return builder;
    }
}