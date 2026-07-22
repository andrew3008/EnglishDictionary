package space.br1440.platform.tracing.otel.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.otel.propagation.OtelTraceparentReader;
import space.br1440.platform.tracing.api.span.builder.ManualSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.RpcClientSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.RpcServerSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.RpcTracing;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.SpanSpecAttributeValue;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.semconv.policy.AttributePolicy;

import java.util.Objects;

final class DefaultRpcTracing implements RpcTracing {

    private final TracingRuntime implementation;
    private final AttributePolicy policy;
    private final OtelTraceparentReader traceparentReader;

    DefaultRpcTracing(@Nonnull TracingRuntime implementation,
                      @Nonnull AttributePolicy policy,
                      @Nonnull OtelTraceparentReader traceparentReader) {
        this.implementation = Objects.requireNonNull(implementation, "implementation");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.traceparentReader = Objects.requireNonNull(traceparentReader, "traceparentReader");
    }

    @Override
    @Nonnull
    public RpcServerSpanBuilder server() {
        return new RpcServerSpanBuilderImpl(implementation, policy, traceparentReader);
    }

    @Override
    @Nonnull
    public RpcClientSpanBuilder client() {
        return new RpcClientSpanBuilderImpl(implementation, policy, traceparentReader);
    }

    private static abstract class AbstractRpcSpanBuilder<B extends ManualSpanBuilder<B>>
            extends AbstractSemanticSpanBuilder<B> {

        AbstractRpcSpanBuilder(@Nonnull TracingRuntime implementation,
                               @Nonnull AttributePolicy policy,
                               @Nonnull OtelTraceparentReader traceparentReader,
                               @Nonnull SpanCategory category,
                               @Nonnull String builderName) {
            super(implementation, policy, traceparentReader, category, category.value(), builderName);
        }

        @Nonnull
        @Override
        protected SpanSpec toSpanSpec() {
            requireAttribute(SemconvKeys.RPC_SYSTEM.getKey(), "system");
            requireAttribute(SemconvKeys.RPC_SERVICE.getKey(), "service");
            requireAttribute(SemconvKeys.RPC_METHOD.getKey(), "method");
            return super.toSpanSpec();
        }

        private void requireAttribute(@Nonnull String key, @Nonnull String label) {
            if (!attributes.containsKey(key)) {
                throw new IllegalArgumentException(label + " is required");
            }
        }
    }

    private static final class RpcServerSpanBuilderImpl extends AbstractRpcSpanBuilder<RpcServerSpanBuilder>
            implements RpcServerSpanBuilder {

        RpcServerSpanBuilderImpl(@Nonnull TracingRuntime implementation,
                                 @Nonnull AttributePolicy policy,
                                 @Nonnull OtelTraceparentReader traceparentReader) {
            super(implementation, policy, traceparentReader, SpanCategory.RPC_SERVER, "RpcServerSpanBuilder");
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

        RpcClientSpanBuilderImpl(@Nonnull TracingRuntime implementation,
                                 @Nonnull AttributePolicy policy,
                                 @Nonnull OtelTraceparentReader traceparentReader) {
            super(implementation, policy, traceparentReader, SpanCategory.RPC_CLIENT, "RpcClientSpanBuilder");
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
