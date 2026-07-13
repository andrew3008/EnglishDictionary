package space.br1440.platform.tracing.core.manual.spec;

import io.opentelemetry.api.common.Attributes;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.experimental.UtilityClass;
import space.br1440.platform.tracing.api.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.spec.*;
import space.br1440.platform.tracing.core.naming.PlatformSpanNameBuilder;
import space.br1440.platform.tracing.core.runtime.otel.SpanSpecAttributeValueConverter;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;
import space.br1440.platform.tracing.core.semconv.policy.ValidatedAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@UtilityClass
public final class SemanticSpanSpecs {

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

        Map<String, SpanSpecAttributeValue> enriched = new LinkedHashMap<>(attributes);
        String platformTypeKey = SemconvKeys.PLATFORM_TYPE.getKey();
        if (!enriched.containsKey(platformTypeKey)) {
            enriched.put(platformTypeKey, SpanSpecAttributeValue.of(category.value()));
        }

        Attributes accumulated = SpanSpecAttributeValueConverter.toAttributes(enriched);
        ValidatedAttributes validated = policy.validateAndNormalize(category, accumulated, builderName);
        String spanName = PlatformSpanNameBuilder.forCategory(category, validated.attributes(), explicitName);
        Map<String, SpanSpecAttributeValue> normalized = SpanSpecAttributeValueConverter.fromAttributes(validated.attributes());

        SpanSpecBuilder builder = SpanSpec.builder(spanName)
                .category(category)
                .reason(SpanSpecReason.PLATFORM_EDGE_CASE);
        applyRelationship(builder, relationship, links);
        applyAttributes(builder, normalized);
        return builder.build();
    }

    private static void applyRelationship(SpanSpecBuilder builder, SpanRelationship relationship, List<RemoteSpanLink> links) {
        switch (relationship) {
            case CHILD -> builder.child();
            case ROOT -> {
                builder.root();
                if (!links.isEmpty()) {
                    builder.linkedTo(links.toArray(RemoteSpanLink[]::new));
                }
            }
            case DETACHED -> builder.detached();
        }
    }

    private static void applyAttributes(SpanSpecBuilder builder, Map<String, SpanSpecAttributeValue> attributes) {
        Map<String, SpanSpecAttributeValue> copy = new LinkedHashMap<>(attributes);
        for (Map.Entry<String, SpanSpecAttributeValue> entry : copy.entrySet()) {
            switch (entry.getValue()) {
                case SpanSpecAttributeValue.StringValue sv -> builder.attribute(entry.getKey(), sv.value());
                case SpanSpecAttributeValue.LongValue lv -> builder.attribute(entry.getKey(), lv.value());
                case SpanSpecAttributeValue.DoubleValue dv -> builder.attribute(entry.getKey(), dv.value());
                case SpanSpecAttributeValue.BooleanValue bv -> builder.attribute(entry.getKey(), bv.value());
                case SpanSpecAttributeValue.StringListValue slv -> builder.stringListAttribute(entry.getKey(), slv.values());
                case SpanSpecAttributeValue.LongListValue llv -> builder.longListAttribute(entry.getKey(), llv.values());
                case SpanSpecAttributeValue.DoubleListValue dlv -> builder.doubleListAttribute(entry.getKey(), dlv.values());
                case SpanSpecAttributeValue.BooleanListValue blv -> builder.booleanListAttribute(entry.getKey(), blv.values());
            }
        }
    }
}
