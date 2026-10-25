package space.br1440.platform.tracing.otel.span.spec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.SpanRelationship;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecAttributeValue;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidator;
import space.br1440.platform.tracing.otel.attributes.validation.ValidatedAttributes;
import space.br1440.platform.tracing.otel.runtime.otel.SpanSpecAttributeValueConverter;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;

import io.opentelemetry.api.common.Attributes;
import lombok.experimental.UtilityClass;

@UtilityClass
final class SemanticSpanSpecs {

    @Nonnull
    public static SpanSpec build(@Nonnull SpanCategory category,
                                 @Nullable String explicitName,
                                 @Nonnull SpanRelationship relationship,
                                 @Nonnull List<RemoteSpanLink> links,
                                 @Nonnull Map<String, SpanSpecAttributeValue> attributes,
                                 @Nonnull SpanAttributeValidator policy,
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

        return SpanSpecBuilderSupport.applyAttributes(
                SpanSpecBuilderSupport.applyRelationship(
                        SpanSpec.builder(spanName)
                                .category(category),
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
}
