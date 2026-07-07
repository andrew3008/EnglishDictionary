package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanLinkContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class SpanSpecImpl implements SpanSpec {

    private final String name;
    private final SpanCategory category;
    private final SpanOptions options;
    private final Map<String, SpanAttributeValue> attributes;
    private final SpanSpecReason reason;
    private final Optional<String> reference;

    SpanSpecImpl(@Nonnull String name,
                 @Nonnull SpanCategory category,
                 @Nonnull SpanOptions options,
                 @Nonnull Map<String, SpanAttributeValue> attributes,
                 @Nonnull SpanSpecReason reason,
                 @Nonnull Optional<String> reference) {
        this.name = Objects.requireNonNull(name, "name");
        this.category = Objects.requireNonNull(category, "category");
        this.options = Objects.requireNonNull(options, "options");
        this.attributes = Map.copyOf(attributes);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.reference = Objects.requireNonNull(reference, "reference");
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
    public SpanOptions options() {
        return options;
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
        return reference;
    }
}
