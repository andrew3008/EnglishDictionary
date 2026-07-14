package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.propagation.OtelTraceparentReader;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

final class DefaultSpanSpecBuilder implements SpanSpecBuilder {

    /**
     * Lazily resolved via {@link ServiceLoader} so that {@code platform-tracing-api}
     * remains free of compile-time OTel dependencies. The implementation
     * ({@code OtelTraceparentReaderImpl}) is registered by {@code platform-tracing-core}
     * through {@code META-INF/services}.
     */
    private static final OtelTraceparentReader TRACEPARENT_READER =
            ServiceLoader.load(OtelTraceparentReader.class)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No OtelTraceparentReader implementation found on classpath. "
                            + "Ensure platform-tracing-core is present at runtime."));

    private final String name;
    private SpanCategory category;
    private SpanRelationship relationship = SpanRelationship.CHILD;
    private boolean relationshipExplicit;
    private final List<RemoteSpanLink> links = new ArrayList<>();
    private final Map<String, SpanSpecAttributeValue> attributes = new LinkedHashMap<>();
    private SpanSpecReason reason;
    private String reference;
    private boolean reasonSet;
    private boolean referenceSet;

    DefaultSpanSpecBuilder(@Nonnull String name) {
        this.name = Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    @Override
    @Nonnull
    public SpanSpecBuilder category(@Nonnull SpanCategory category) {
        this.category = Objects.requireNonNull(category, "category");
        return this;
    }

    @Override
    @Nonnull
    public SpanSpecBuilder child() {
        return setRelationship(SpanRelationship.CHILD);
    }

    @Override
    @Nonnull
    public SpanSpecBuilder root() {
        return setRelationship(SpanRelationship.ROOT);
    }

    @Override
    @Nonnull
    public SpanSpecBuilder detached() {
        return setRelationship(SpanRelationship.DETACHED);
    }

    @Override
    @Nonnull
    public SpanSpecBuilder linkedTo(@Nonnull RemoteSpanLink... links) {
        Objects.requireNonNull(links, "links");

        for (RemoteSpanLink link : links) {
            Objects.requireNonNull(link, "link");
            this.links.add(link);
        }

        return this;
    }

    @Override
    @Nonnull
    public SpanSpecBuilder fromTraceparent(@Nonnull String... traceparents) {
        Objects.requireNonNull(traceparents, "traceparents");

        for (String traceparent : traceparents) {
            RemoteSpanLink link = TRACEPARENT_READER.require(traceparent);
            linkedTo(link);
        }

        return this;
    }

    @Override
    @Nonnull
    public SpanSpecBuilder attribute(@Nonnull String key, @Nonnull String value) {
        return putAttribute(key, SpanSpecAttributeValue.of(value));
    }

    @Override
    @Nonnull
    public SpanSpecBuilder attribute(@Nonnull String key, long value) {
        return putAttribute(key, SpanSpecAttributeValue.of(value));
    }

    @Override
    @Nonnull
    public SpanSpecBuilder attribute(@Nonnull String key, double value) {
        return putAttribute(key, SpanSpecAttributeValue.of(value));
    }

    @Override
    @Nonnull
    public SpanSpecBuilder attribute(@Nonnull String key, boolean value) {
        return putAttribute(key, SpanSpecAttributeValue.of(value));
    }

    @Override
    @Nonnull
    public SpanSpecBuilder stringListAttribute(@Nonnull String key, @Nonnull List<String> values) {
        return putAttribute(key, SpanSpecAttributeValue.stringList(values));
    }

    @Override
    @Nonnull
    public SpanSpecBuilder longListAttribute(@Nonnull String key, @Nonnull List<Long> values) {
        return putAttribute(key, SpanSpecAttributeValue.longList(values));
    }

    @Override
    @Nonnull
    public SpanSpecBuilder doubleListAttribute(@Nonnull String key, @Nonnull List<Double> values) {
        return putAttribute(key, SpanSpecAttributeValue.doubleList(values));
    }

    @Override
    @Nonnull
    public SpanSpecBuilder booleanListAttribute(@Nonnull String key, @Nonnull List<Boolean> values) {
        return putAttribute(key, SpanSpecAttributeValue.booleanList(values));
    }

    @Override
    @Nonnull
    public SpanSpecBuilder reason(@Nonnull SpanSpecReason reason) {
        Objects.requireNonNull(reason, "reason");

        if (reasonSet) {
            throw new IllegalStateException("reason(...) already set");
        }

        this.reason = reason;
        reasonSet = true;
        return this;
    }

    @Override
    @Nonnull
    public SpanSpecBuilder reference(@Nonnull String reference) {
        Objects.requireNonNull(reference, "reference");

        if (referenceSet) {
            throw new IllegalStateException("reference(...) already set");
        }

        if (reference.isBlank()) {
            throw new IllegalArgumentException("reference must not be blank");
        }

        this.reference = reference;
        referenceSet = true;
        return this;
    }

    @Override
    @Nonnull
    public SpanSpec build() {
        if (category == null) {
            throw new IllegalStateException("category(...) is required");
        }

        if (reason == null) {
            throw new IllegalStateException("reason(...) is required");
        }

        if (reason == SpanSpecReason.TEMPORARY_WORKAROUND && reference == null) {
            throw new IllegalStateException("TEMPORARY_WORKAROUND requires reference(...)");
        }

        SpanRelationshipSpec relationshipSpec = ImmutableSpanRelationshipSpec.of(relationship, List.copyOf(links));
        ImmutableSpanRelationshipSpec.validateRelationshipLinks(relationship, links);
        return new SpanSpecImpl(name, category, relationshipSpec, attributes, reason, reference);
    }

    private SpanSpecBuilder setRelationship(@Nonnull SpanRelationship relationship) {
        Objects.requireNonNull(relationship, "relationship");

        if (relationshipExplicit) {
            throw new IllegalStateException("relationship already set; first relationship setter wins");
        }

        this.relationship = relationship;
        relationshipExplicit = true;
        return this;
    }

    private SpanSpecBuilder putAttribute(@Nonnull String key, @Nonnull SpanSpecAttributeValue value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");

        if (key.isBlank()) {
            throw new IllegalArgumentException("attribute key must not be blank");
        }

        if (attributes.containsKey(key)) {
            throw new IllegalStateException("duplicate attribute key: " + key);
        }

        attributes.put(key, value);
        return this;
    }
}
