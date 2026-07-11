package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.manual.ManualSpanBuilder;
import space.br1440.platform.tracing.api.span.RemoteContext;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.api.span.spec.*;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;
import space.br1440.platform.tracing.core.manual.spec.OperationSpanSpecs;
import space.br1440.platform.tracing.core.manual.spec.SemanticSpanSpecs;
import space.br1440.platform.tracing.core.runtime.TracingRuntime;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;

import java.util.*;
import java.util.function.Supplier;

abstract class AbstractSemanticSpanBuilder<B extends ManualSpanBuilder<B>> implements ManualSpanBuilder<B> {

    protected final TracingRuntime implementation;
    protected final AttributePolicy policy;
    protected final SpanCategory category;
    protected final String explicitName;
    protected final String builderName;
    private boolean relationshipExplicit = false;
    protected SpanRelationship relationship = SpanRelationship.CHILD;
    protected final List<SpanLinkContext> links = new ArrayList<>();
    protected final Map<String, SpanAttributeValue> attributes = new LinkedHashMap<>();

    AbstractSemanticSpanBuilder(@Nonnull TracingRuntime implementation,
                                @Nonnull AttributePolicy policy,
                                @Nonnull SpanCategory category,
                                @Nonnull String explicitName,
                                @Nonnull String builderName) {
        this.implementation = Objects.requireNonNull(implementation, "implementation");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.category = Objects.requireNonNull(category, "category");
        this.explicitName = Objects.requireNonNull(explicitName, "explicitName");
        this.builderName = Objects.requireNonNull(builderName, "builderName");

        if (explicitName.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    protected abstract B self();

    protected void putAttribute(@Nonnull String key, @Nonnull SpanAttributeValue value) {
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
    public B linkedTo(@Nonnull SpanLinkContext... linkContexts) {
        Objects.requireNonNull(linkContexts, "linkContexts");

        for (SpanLinkContext link : linkContexts) {
            links.add(Objects.requireNonNull(link, "link"));
        }

        return self();
    }

    @Override
    @Nonnull
    public B fromRemoteContext(@Nonnull String... traceparents) {
        Objects.requireNonNull(traceparents, "traceparents");

        for (String traceparent : traceparents) {
            linkedTo(RemoteContext.requireTraceparent(Objects.requireNonNull(traceparent, "traceparent")));
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
