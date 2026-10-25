package space.br1440.platform.tracing.otel.span.spec;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.spec.SpanRelationship;
import space.br1440.platform.tracing.api.span.spec.SpanSpecAttributeValue;
import space.br1440.platform.tracing.api.span.spec.SpanSpecBuilder;

final class SpanSpecBuilderSupport {

    private SpanSpecBuilderSupport() {
    }

    static SpanSpecBuilder applyRelationship(SpanSpecBuilder builder,
                                             SpanRelationship relationship,
                                             List<RemoteSpanLink> links) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(relationship, "relationship");
        Objects.requireNonNull(links, "links");

        return switch (relationship) {
            case CHILD -> builder.child();
            case DETACHED -> builder.detached();
            case ROOT -> links.isEmpty()
                    ? builder.root()
                    : builder.root().linkedTo(links.toArray(RemoteSpanLink[]::new));
        };
    }

    static SpanSpecBuilder applyAttributes(SpanSpecBuilder builder,
                                           Map<String, SpanSpecAttributeValue> attributes) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(attributes, "attributes");

        for (Map.Entry<String, SpanSpecAttributeValue> entry : attributes.entrySet()) {
            builder = switch (entry.getValue()) {
                case SpanSpecAttributeValue.StringValue value -> builder.attribute(entry.getKey(), value.value());
                case SpanSpecAttributeValue.LongValue value -> builder.attribute(entry.getKey(), value.value());
                case SpanSpecAttributeValue.DoubleValue value -> builder.attribute(entry.getKey(), value.value());
                case SpanSpecAttributeValue.BooleanValue value -> builder.attribute(entry.getKey(), value.value());
                case SpanSpecAttributeValue.StringListValue value -> builder.stringListAttribute(entry.getKey(), value.values());
                case SpanSpecAttributeValue.LongListValue value -> builder.longListAttribute(entry.getKey(), value.values());
                case SpanSpecAttributeValue.DoubleListValue value -> builder.doubleListAttribute(entry.getKey(), value.values());
                case SpanSpecAttributeValue.BooleanListValue value -> builder.booleanListAttribute(entry.getKey(), value.values());
            };
        }

        return builder;
    }
}
