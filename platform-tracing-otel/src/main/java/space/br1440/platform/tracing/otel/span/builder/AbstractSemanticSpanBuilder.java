package space.br1440.platform.tracing.otel.span.builder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.builder.ManualSpanBuilder;
import space.br1440.platform.tracing.api.span.spec.SpanExecution;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanRelationship;
import space.br1440.platform.tracing.api.span.spec.SpanRelationshipSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecAttributeValue;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;
import space.br1440.platform.tracing.otel.propagation.OtelTraceparentReader;
import space.br1440.platform.tracing.otel.span.spec.DefaultSpanSpecFactory;

abstract class AbstractSemanticSpanBuilder<B extends ManualSpanBuilder<B>> implements ManualSpanBuilder<B> {

    protected final DefaultSpanSpecFactory specFactory;
    protected final OtelTraceparentReader traceparentReader;
    protected final SpanCategory category;
    protected final String explicitName;
    protected final String builderName;
    private boolean relationshipExplicit = false;
    protected SpanRelationship relationship = SpanRelationship.CHILD;
    protected final List<RemoteSpanLink> links = new ArrayList<>();
    protected final Map<String, SpanSpecAttributeValue> attributes = new LinkedHashMap<>();

    AbstractSemanticSpanBuilder(@Nonnull DefaultSpanSpecFactory specFactory,
                                @Nonnull OtelTraceparentReader traceparentReader,
                                @Nonnull SpanCategory category,
                                @Nonnull String explicitName,
                                @Nonnull String builderName) {
        this.specFactory = Objects.requireNonNull(specFactory, "specFactory");
        this.traceparentReader = Objects.requireNonNull(traceparentReader, "traceparentReader");
        this.category = Objects.requireNonNull(category, "category");
        this.explicitName = Objects.requireNonNull(explicitName, "explicitName");
        this.builderName = Objects.requireNonNull(builderName, "builderName");

        if (explicitName.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    protected abstract B self();

    protected void putAttribute(@Nonnull String key, @Nonnull SpanSpecAttributeValue value) {
        if (attributes.containsKey(key)) {
            throw new IllegalStateException("duplicate attribute key: " + key);
        }

        attributes.put(key, value);
    }

    @Override
    @Nonnull
    public B child() {
        setRelationship(SpanRelationship.CHILD);
        return self();
    }

    @Override
    @Nonnull
    public B root() {
        setRelationship(SpanRelationship.ROOT);
        return self();
    }

    @Override
    @Nonnull
    public B detached() {
        setRelationship(SpanRelationship.DETACHED);
        return self();
    }

    private void setRelationship(SpanRelationship requested) {
        if (relationshipExplicit) {
            throw new IllegalStateException(
                    "relationship already set; first relationship setter wins (current=" + relationship + ", requested=" + requested + ")"
            );
        }

        this.relationship = requested;
        this.relationshipExplicit = true;
    }

    @Override
    @Nonnull
    public B linkedTo(@Nonnull RemoteSpanLink... linkContexts) {
        Objects.requireNonNull(linkContexts, "linkContexts");

        for (RemoteSpanLink link : linkContexts) {
            links.add(Objects.requireNonNull(link, "link"));
        }

        return self();
    }

    @Override
    @Nonnull
    public B fromTraceparent(@Nonnull String... traceparents) {
        Objects.requireNonNull(traceparents, "traceparents");

        for (String traceparent : traceparents) {
            linkedTo(traceparentReader.require(Objects.requireNonNull(traceparent, "traceparent")));
        }

        return self();
    }

    @Override
    @Nonnull
    public SpanHandle start() {
        return execution().start();
    }

    @Override
    public void run(@Nonnull Runnable action) {
        execution().run(action);
    }

    @Override
    @Nonnull
    public <T> T call(@Nonnull Supplier<T> supplier) {
        return execution().call(supplier);
    }

    @Override
    @Nonnull
    public <T> T callChecked(@Nonnull ThrowingSupplier<T> supplier) throws Exception {
        return execution().callChecked(supplier);
    }

    protected void validateBeforeExecution() {
    }

    @Nonnull
    private SpanExecution execution() {
        validateBeforeExecution();
        SpanRelationshipSpec.validateRelationshipLinks(relationship, links);

        if (category == SpanCategory.INTERNAL) {
            return specFactory.fromOperation(category, explicitName, relationship, List.copyOf(links), builderName);
        }

        return specFactory.fromSemanticBuilder(
                category,
                explicitName,
                relationship,
                List.copyOf(links),
                attributes,
                builderName);
    }
}
