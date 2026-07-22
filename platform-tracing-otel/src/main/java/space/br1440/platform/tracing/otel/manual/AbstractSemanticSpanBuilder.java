package space.br1440.platform.tracing.otel.manual;

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
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanRelationship;
import space.br1440.platform.tracing.api.span.spec.SpanRelationshipSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecAttributeValue;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;
import space.br1440.platform.tracing.otel.manual.spec.OperationSpanSpecs;
import space.br1440.platform.tracing.otel.manual.spec.SemanticSpanSpecs;
import space.br1440.platform.tracing.otel.propagation.OtelTraceparentReader;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.semconv.policy.AttributePolicy;

abstract class AbstractSemanticSpanBuilder<B extends ManualSpanBuilder<B>> implements ManualSpanBuilder<B> {

    protected final TracingRuntime implementation;
    protected final AttributePolicy policy;
    protected final OtelTraceparentReader traceparentReader;
    protected final SpanCategory category;
    protected final String explicitName;
    protected final String builderName;
    private boolean relationshipExplicit = false;
    protected SpanRelationship relationship = SpanRelationship.CHILD;
    protected final List<RemoteSpanLink> links = new ArrayList<>();
    protected final Map<String, SpanSpecAttributeValue> attributes = new LinkedHashMap<>();

    AbstractSemanticSpanBuilder(@Nonnull TracingRuntime implementation,
                                @Nonnull AttributePolicy policy,
                                @Nonnull OtelTraceparentReader traceparentReader,
                                @Nonnull SpanCategory category,
                                @Nonnull String explicitName,
                                @Nonnull String builderName) {
        this.implementation = Objects.requireNonNull(implementation, "implementation");
        this.policy = Objects.requireNonNull(policy, "policy");
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
        return implementation.startSpan(toSpanSpec());
    }

    @Override
    public void run(@Nonnull Runnable action) {
        ScopedExecution.run(this::start, action);
    }

    @Override
    @Nonnull
    public <T> T call(@Nonnull Supplier<T> supplier) {
        return ScopedExecution.call(this::start, supplier);
    }

    @Override
    @Nonnull
    public <T> T callChecked(@Nonnull ThrowingSupplier<T> supplier) throws Exception {
        return ScopedExecution.callChecked(this::start, supplier);
    }

    @Nonnull
    protected SpanSpec toSpanSpec() {
        SpanRelationshipSpec.validateRelationshipLinks(relationship, links);

        if (category == SpanCategory.INTERNAL) {
            return OperationSpanSpecs.from(explicitName, category, relationship, List.copyOf(links));
        }

        return SemanticSpanSpecs.build(
                category,
                explicitName,
                relationship,
                List.copyOf(links),
                attributes,
                policy,
                builderName);
    }
}
