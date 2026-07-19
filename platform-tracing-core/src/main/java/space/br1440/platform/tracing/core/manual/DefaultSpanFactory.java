package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.propagation.OtelTraceparentReader;
import space.br1440.platform.tracing.api.span.SpanFactory;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.builder.OperationSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.TransportTracing;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanExecution;
import space.br1440.platform.tracing.core.runtime.TracingRuntime;
import space.br1440.platform.tracing.core.propagation.OtelTraceparentReaderImpl;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;

import java.util.Objects;
import java.util.Optional;

public final class DefaultSpanFactory implements SpanFactory {

    private static final String FROM_SPEC_BUILDER_NAME = "SpanFactory.fromSpec";

    private final TracingRuntime implementation;
    private final AttributePolicy policy;
    private final OtelTraceparentReader traceparentReader;

    public DefaultSpanFactory(@Nonnull TracingRuntime implementation,
                              @Nonnull AttributePolicy policy) {
        this(implementation, policy, readerFor(implementation));
    }

    DefaultSpanFactory(@Nonnull TracingRuntime implementation,
                       @Nonnull AttributePolicy policy,
                       @Nonnull OtelTraceparentReader traceparentReader) {
        this.implementation = Objects.requireNonNull(implementation, "implementation");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.traceparentReader = Objects.requireNonNull(traceparentReader, "traceparentReader");
    }

    @Override
    @Nonnull
    public OperationSpanBuilder operation(@Nonnull String name) {
        Objects.requireNonNull(name, "name");
        return new OperationSpanBuilderImpl(implementation, policy, traceparentReader, name);
    }

    @Override
    @Nonnull
    public TransportTracing transport() {
        return new DefaultTransportTracing(implementation, policy, traceparentReader);
    }

    @Override
    @Nonnull
    public SpanExecution fromSpec(@Nonnull SpanSpec spec) {
        return new SpanExecutionImpl(
                implementation,
                Objects.requireNonNull(spec, "spec"),
                policy,
                FROM_SPEC_BUILDER_NAME);
    }

    static OtelTraceparentReader readerFor(TracingRuntime implementation) {
        Objects.requireNonNull(implementation, "implementation");
        return switch (implementation.state().mode()) {
            case ENABLED, TEST -> OtelTraceparentReaderImpl.INSTANCE;
            case DISABLED_BY_CONFIGURATION, UNAVAILABLE, NOOP -> DisabledTraceparentReader.INSTANCE;
        };
    }

    private enum DisabledTraceparentReader implements OtelTraceparentReader {
        INSTANCE;

        @Override
        public Optional<RemoteSpanLink> read(String traceparent) {
            return Optional.empty();
        }

        @Override
        public Optional<RemoteSpanLink> read(String traceparent, String tracestate) {
            return Optional.empty();
        }

        @Override
        public RemoteSpanLink require(String traceparent) {
            throw new IllegalArgumentException("traceparent parsing is disabled");
        }
    }
}
