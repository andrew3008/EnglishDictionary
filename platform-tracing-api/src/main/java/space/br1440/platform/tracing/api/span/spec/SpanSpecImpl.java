package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.span.SpanCategory;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class SpanSpecImpl implements SpanSpec {

    private final String name;
    private final SpanCategory category;
    private final SpanRelationshipSpec relationship;
    private final Map<String, SpanAttributeValue> attributes;
    private final SpanSpecReason reason;
    private final String reference;

    SpanSpecImpl(@Nonnull String name,
                 @Nonnull SpanCategory category,
                 @Nonnull SpanRelationshipSpec relationship,
                 @Nonnull Map<String, SpanAttributeValue> attributes,
                 @Nonnull SpanSpecReason reason,
                 @Nullable String reference) {
        this.name = Objects.requireNonNull(name, "name");
        this.category = Objects.requireNonNull(category, "category");
        this.relationship = Objects.requireNonNull(relationship, "relationship");
        this.attributes = Map.copyOf(attributes);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.reference = reference;
    }

    @Override
    @Nonnull
    public String name() {
        return name;
    }

    @Override
    @Nonnull
    public SpanCategory category() {
        return category;
    }

    @Override
    @Nonnull
    public SpanRelationshipSpec relationship() {
        return relationship;
    }

    @Override
    @Nonnull
    public Map<String, SpanAttributeValue> attributes() {
        return attributes;
    }

    @Override
    @Nonnull
    public SpanSpecReason reason() {
        return reason;
    }

    @Override
    @Nonnull
    public Optional<String> reference() {
        return Optional.ofNullable(reference);
    }
}
