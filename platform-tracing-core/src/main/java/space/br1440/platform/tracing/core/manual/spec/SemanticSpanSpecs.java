package space.br1440.platform.tracing.core.manual.spec;

import io.opentelemetry.api.common.Attributes;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.experimental.UtilityClass;
import space.br1440.platform.tracing.api.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.api.span.spec.*;
import space.br1440.platform.tracing.core.naming.PlatformSpanNameBuilder;
import space.br1440.platform.tracing.core.runtime.otel.SpanAttributeValueConverter;
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
                                 @Nonnull Topology topology,
                                 @Nonnull List<SpanLinkContext> links,
                                 @Nonnull Map<String, SpanAttributeValue> attributes,
                                 @Nonnull AttributePolicy policy,
                                 @Nonnull String builderName) {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(links, "links");
        Objects.requireNonNull(attributes, "attributes");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(builderName, "builderName");

        Map<String, SpanAttributeValue> enriched = new LinkedHashMap<>(attributes);
        String platformTypeKey = SemconvKeys.PLATFORM_TYPE.getKey();
        if (!enriched.containsKey(platformTypeKey)) {
            enriched.put(platformTypeKey, SpanAttributeValue.of(category.value()));
        }

        Attributes accumulated = SpanAttributeValueConverter.toAttributes(enriched);
        ValidatedAttributes validated = policy.validateAndNormalize(category, accumulated, builderName);
        String spanName = PlatformSpanNameBuilder.forCategory(category, validated.attributes(), explicitName);
        Map<String, SpanAttributeValue> normalized = SpanAttributeValueConverter.fromAttributes(validated.attributes());

        SpanSpecBuilder builder = SpanSpec.builder(spanName)
                .category(category)
                .reason(SpanSpecReason.PLATFORM_EDGE_CASE);
        applyTopology(builder, topology, links);
        applyAttributes(builder, normalized);
        return builder.build();
    }

    private static void applyTopology(SpanSpecBuilder builder, Topology topology, List<SpanLinkContext> links) {
        switch (topology) {
            case CHILD -> builder.child();
            case ROOT -> {
                builder.root();
                if (!links.isEmpty()) {
                    builder.linkedTo(links.toArray(SpanLinkContext[]::new));
                }
            }
            case DETACHED -> builder.detached();
        }
    }

    private static void applyAttributes(SpanSpecBuilder builder, Map<String, SpanAttributeValue> attributes) {
        Map<String, SpanAttributeValue> copy = new LinkedHashMap<>(attributes);
        for (Map.Entry<String, SpanAttributeValue> entry : copy.entrySet()) {
            switch (entry.getValue()) {
                case SpanAttributeValue.StringValue sv -> builder.attribute(entry.getKey(), sv.value());
                case SpanAttributeValue.LongValue lv -> builder.attribute(entry.getKey(), lv.value());
                case SpanAttributeValue.DoubleValue dv -> builder.attribute(entry.getKey(), dv.value());
                case SpanAttributeValue.BooleanValue bv -> builder.attribute(entry.getKey(), bv.value());
                case SpanAttributeValue.StringListValue slv -> builder.stringListAttribute(entry.getKey(), slv.values());
                case SpanAttributeValue.LongListValue llv -> builder.longListAttribute(entry.getKey(), llv.values());
                case SpanAttributeValue.DoubleListValue dlv -> builder.doubleListAttribute(entry.getKey(), dlv.values());
                case SpanAttributeValue.BooleanListValue blv -> builder.booleanListAttribute(entry.getKey(), blv.values());
            }
        }
    }
}
