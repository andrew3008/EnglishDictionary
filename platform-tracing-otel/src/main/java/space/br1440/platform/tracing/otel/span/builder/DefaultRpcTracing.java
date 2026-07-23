package space.br1440.platform.tracing.otel.span.builder;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.builder.ManualSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.RpcClientSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.RpcServerSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.RpcTracing;
import space.br1440.platform.tracing.api.span.spec.SpanSpecAttributeValue;
import space.br1440.platform.tracing.otel.propagation.OtelTraceparentReader;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;
import space.br1440.platform.tracing.otel.span.spec.DefaultSpanSpecFactory;

import java.util.Objects;

final class DefaultRpcTracing implements RpcTracing {

    private final DefaultSpanSpecFactory specFactory;
    private final OtelTraceparentReader traceparentReader;

    DefaultRpcTracing(@Nonnull DefaultSpanSpecFactory specFactory,
                      @Nonnull OtelTraceparentReader traceparentReader) {
        this.specFactory = Objects.requireNonNull(specFactory, "specFactory");
        this.traceparentReader = Objects.requireNonNull(traceparentReader, "traceparentReader");
    }

    @Override
    @Nonnull
    public RpcServerSpanBuilder server() {
        return new RpcServerSpanBuilderImpl(specFactory, traceparentReader);
    }

    @Override
    @Nonnull
    public RpcClientSpanBuilder client() {
        return new RpcClientSpanBuilderImpl(specFactory, traceparentReader);
    }

    private static abstract class AbstractRpcSpanBuilder<B extends ManualSpanBuilder<B>>
            extends AbstractSemanticSpanBuilder<B> {

        AbstractRpcSpanBuilder(@Nonnull DefaultSpanSpecFactory specFactory,
                               @Nonnull OtelTraceparentReader traceparentReader,
                               @Nonnull SpanCategory category,
                               @Nonnull String builderName) {
            super(specFactory, traceparentReader, category, category.value(), builderName);
        }

        @Override
        protected void validateBeforeExecution() {
            requireAttribute(SemconvKeys.RPC_SYSTEM.getKey(), "system");
            requireAttribute(SemconvKeys.RPC_SERVICE.getKey(), "service");
            requireAttribute(SemconvKeys.RPC_METHOD.getKey(), "method");
        }

        private void requireAttribute(@Nonnull String key, @Nonnull String label) {
            if (!attributes.containsKey(key)) {
                throw new IllegalArgumentException(label + " is required");
            }
        }
    }

    private static final class RpcServerSpanBuilderImpl extends AbstractRpcSpanBuilder<RpcServerSpanBuilder>
            implements RpcServerSpanBuilder {

        RpcServerSpanBuilderImpl(@Nonnull DefaultSpanSpecFactory specFactory,
                                 @Nonnull OtelTraceparentReader traceparentReader) {
            super(specFactory, traceparentReader, SpanCategory.RPC_SERVER, "RpcServerSpanBuilder");
        }

        @Override
        protected RpcServerSpanBuilder self() {
            return this;
        }

        @Override
        @Nonnull
        public RpcServerSpanBuilder system(@Nonnull String rpcSystem) {
            putAttribute(SemconvKeys.RPC_SYSTEM.getKey(), SpanSpecAttributeValue.of(rpcSystem));
            return this;
        }

        @Override
        @Nonnull
        public RpcServerSpanBuilder service(@Nonnull String service) {
            putAttribute(SemconvKeys.RPC_SERVICE.getKey(), SpanSpecAttributeValue.of(service));
            return this;
        }

        @Override
        @Nonnull
        public RpcServerSpanBuilder method(@Nonnull String method) {
            putAttribute(SemconvKeys.RPC_METHOD.getKey(), SpanSpecAttributeValue.of(method));
            return this;
        }
    }

    private static final class RpcClientSpanBuilderImpl extends AbstractRpcSpanBuilder<RpcClientSpanBuilder>
            implements RpcClientSpanBuilder {

        RpcClientSpanBuilderImpl(@Nonnull DefaultSpanSpecFactory specFactory,
                                 @Nonnull OtelTraceparentReader traceparentReader) {
            super(specFactory, traceparentReader, SpanCategory.RPC_CLIENT, "RpcClientSpanBuilder");
        }

        @Override
        protected RpcClientSpanBuilder self() {
            return this;
        }

        @Override
        @Nonnull
        public RpcClientSpanBuilder system(@Nonnull String rpcSystem) {
            putAttribute(SemconvKeys.RPC_SYSTEM.getKey(), SpanSpecAttributeValue.of(rpcSystem));
            return this;
        }

        @Override
        @Nonnull
        public RpcClientSpanBuilder service(@Nonnull String service) {
            putAttribute(SemconvKeys.RPC_SERVICE.getKey(), SpanSpecAttributeValue.of(service));
            return this;
        }

        @Override
        @Nonnull
        public RpcClientSpanBuilder method(@Nonnull String method) {
            putAttribute(SemconvKeys.RPC_METHOD.getKey(), SpanSpecAttributeValue.of(method));
            return this;
        }

        @Override
        @Nonnull
        public RpcClientSpanBuilder serverAddress(@Nonnull String address) {
            putAttribute(SemconvKeys.SERVER_ADDRESS.getKey(), SpanSpecAttributeValue.of(address));
            return this;
        }
    }
}
