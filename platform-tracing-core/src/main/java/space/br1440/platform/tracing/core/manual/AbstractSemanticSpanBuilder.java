package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.manual.PlatformSpanBuilder;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.RemoteContext;
import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.api.span.spec.SpanAttributeValue;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanOptions;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.Topology;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;
import space.br1440.platform.tracing.core.impl.OperationSpanSpecs;
import space.br1440.platform.tracing.core.impl.SemanticSpanSpecs;
import space.br1440.platform.tracing.core.impl.TracingImplementation;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

abstract class AbstractSemanticSpanBuilder<B extends PlatformSpanBuilder<B>> implements PlatformSpanBuilder<B> {

    protected final TracingImplementation implementation;
    protected final AttributePolicy policy;
    protected final SpanCategory category;
    protected final String explicitName;
    protected final String builderName;
    private boolean topologyExplicit = false;
    protected Topology topology = Topology.CHILD;
    protected final List<SpanLinkContext> links = new ArrayList<>();
    protected final Map<String, SpanAttributeValue> attributes = new LinkedHashMap<>();

    AbstractSemanticSpanBuilder(@Nonnull TracingImplementation implementation,
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
    @SuppressWarnings("unchecked")
    public B child() {
        setTopology(Topology.CHILD);
        return (B) self();
    }

    @Override
    @Nonnull
    @SuppressWarnings("unchecked")
    public B root() {
        setTopology(Topology.ROOT);
        return (B) self();
    }

    @Override
    @Nonnull
    @SuppressWarnings("unchecked")
    public B detached() {
        setTopology(Topology.DETACHED);
        return (B) self();
    }

    /**
     * First explicit topology setter wins; repeated explicit calls fail fast.
     * Mirrors {@code DefaultSpanSpecBuilder.setTopology} policy (remediation B05).
     */
    private void setTopology(Topology requested) {
        if (topologyExplicit) {
            throw new IllegalStateException(
                    "topology already set; first topology setter wins (current=" + topology
                            + ", requested=" + requested + ")");
        }
        this.topology = requested;
        this.topologyExplicit = true;
    }

    @Override
    @Nonnull
    @SuppressWarnings("unchecked")
    public B linkedTo(@Nonnull SpanLinkContext... linkContexts) {
        Objects.requireNonNull(linkContexts, "linkContexts");
        for (SpanLinkContext link : linkContexts) {
            links.add(Objects.requireNonNull(link, "link"));
        }
        return (B) self();
    }

    @Override
    @Nonnull
    @SuppressWarnings("unchecked")
    public B fromRemoteContext(@Nonnull String... traceparents) {
        Objects.requireNonNull(traceparents, "traceparents");
        for (String traceparent : traceparents) {
            linkedTo(RemoteContext.requireTraceparent(Objects.requireNonNull(traceparent, "traceparent")));
        }
        return (B) self();
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
        SpanOptions.validateTopologyLinks(topology, links);
        if (category == SpanCategory.INTERNAL) {
            return OperationSpanSpecs.from(explicitName, category, topology, List.copyOf(links));
        }
        return SemanticSpanSpecs.build(
                category,
                explicitName,
                topology,
                List.copyOf(links),
                attributes,
                policy,
                builderName);
    }
}
