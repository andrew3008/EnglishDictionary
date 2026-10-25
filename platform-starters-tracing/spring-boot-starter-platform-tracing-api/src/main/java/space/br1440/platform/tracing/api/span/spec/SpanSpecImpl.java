package space.br1440.platform.tracing.api.span.spec;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.span.SpanCategory;

final class SpanSpecImpl implements SpanSpec {

    private final String name;
    private final SpanCategory category;
    private final SpanRelationshipSpec relationship;
    private final Map<String, SpanSpecAttributeValue> attributes;
    private final SpanSpecReason reason;
    private final String reference;

    SpanSpecImpl(@Nonnull String name,
                 @Nonnull SpanCategory category,
                 @Nonnull SpanRelationshipSpec relationship,
                 @Nonnull Map<String, SpanSpecAttributeValue> attributes,
                 @Nullable SpanSpecReason reason,
                 @Nullable String reference) {
        this.name = Objects.requireNonNull(name, "name");
        this.category = Objects.requireNonNull(category, "category");
        this.relationship = Objects.requireNonNull(relationship, "relationship");
        this.attributes = Map.copyOf(attributes);
        this.reason = reason;
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
    public Map<String, SpanSpecAttributeValue> attributes() {
        return attributes;
    }

    @Override
    @Nonnull
    public Optional<SpanSpecReason> reason() {
        return Optional.ofNullable(reason);
    }

    @Override
    @Nonnull
    public Optional<String> reference() {
        return Optional.ofNullable(reference);
    }
}
