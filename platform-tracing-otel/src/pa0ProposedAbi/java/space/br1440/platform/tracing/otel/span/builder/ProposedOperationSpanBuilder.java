package space.br1440.platform.tracing.otel.span.builder;

import java.util.Objects;
import java.util.function.Supplier;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.builder.OperationSpanBuilder;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;
import space.br1440.platform.tracing.otel.propagation.OtelTraceparentReader;
import space.br1440.platform.tracing.otel.span.spec.DefaultSpanSpecFactory;

/**
 * PA-0 spike: builder без runtime/policy — lifecycle делегирует в {@link DefaultSpanSpecFactory}.
 */
final class ProposedOperationSpanBuilder implements OperationSpanBuilder {

    private final DefaultSpanSpecFactory specFactory;
    private final OtelTraceparentReader traceparentReader;
    private final String name;

    ProposedOperationSpanBuilder(@Nonnull DefaultSpanSpecFactory specFactory,
                                 @Nonnull OtelTraceparentReader traceparentReader,
                                 @Nonnull String name) {
        this.specFactory = Objects.requireNonNull(specFactory, "specFactory");
        this.traceparentReader = Objects.requireNonNull(traceparentReader, "traceparentReader");
        this.name = Objects.requireNonNull(name, "name");
    }

    @Override
    @Nonnull
    public OperationSpanBuilder child() {
        return this;
    }

    @Override
    @Nonnull
    public OperationSpanBuilder root() {
        return this;
    }

    @Override
    @Nonnull
    public OperationSpanBuilder detached() {
        return this;
    }

    @Override
    @Nonnull
    public OperationSpanBuilder linkedTo(@Nonnull RemoteSpanLink... linkContexts) {
        Objects.requireNonNull(linkContexts, "linkContexts");
        return this;
    }

    @Override
    @Nonnull
    public OperationSpanBuilder fromTraceparent(@Nonnull String... traceparents) {
        Objects.requireNonNull(traceparents, "traceparents");
        return this;
    }

    @Nonnull
    private SpanSpec rawSpec() {
        return SpanSpec.builder(name)
                .category(SpanCategory.INTERNAL)
                .reason(SpanSpecReason.PLATFORM_EDGE_CASE)
                .build();
    }

    @Override
    @Nonnull
    public SpanHandle start() {
        return specFactory.fromBuilderRawSpec(rawSpec(), "ProposedOperationSpanBuilder").start();
    }

    @Override
    public void run(@Nonnull Runnable action) {
        specFactory.fromBuilderRawSpec(rawSpec(), "ProposedOperationSpanBuilder").run(action);
    }

    @Override
    @Nonnull
    public <T> T call(@Nonnull Supplier<T> supplier) {
        return specFactory.fromBuilderRawSpec(rawSpec(), "ProposedOperationSpanBuilder").call(supplier);
    }

    @Override
    @Nonnull
    public <T> T callChecked(@Nonnull ThrowingSupplier<T> supplier) throws Exception {
        return specFactory.fromBuilderRawSpec(rawSpec(), "ProposedOperationSpanBuilder").callChecked(supplier);
    }
}
