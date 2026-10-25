package space.br1440.platform.tracing.otel.span;

import java.util.Objects;
import java.util.Optional;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.SpanFactory;
import space.br1440.platform.tracing.api.span.builder.OperationSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.TransportTracing;
import space.br1440.platform.tracing.api.span.spec.SpanExecution;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidator;
import space.br1440.platform.tracing.otel.propagation.OtelTraceparentReader;
import space.br1440.platform.tracing.otel.propagation.OtelTraceparentReaderImpl;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.span.builder.DefaultTransportTracing;
import space.br1440.platform.tracing.otel.span.builder.OperationSpanBuilderImpl;
import space.br1440.platform.tracing.otel.span.spec.DefaultSpanSpecFactory;

public final class DefaultSpanFactory implements SpanFactory {

    private final DefaultSpanSpecFactory specFactory;
    private final OtelTraceparentReader traceparentReader;

    public DefaultSpanFactory(@Nonnull TracingRuntime runtime,
                              @Nonnull SpanAttributeValidator policy) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(policy, "policy");

        this.specFactory = new DefaultSpanSpecFactory(runtime, policy);
        this.traceparentReader = readerFor(runtime);
    }

    @Override
    @Nonnull
    public OperationSpanBuilder operation(@Nonnull String name) {
        Objects.requireNonNull(name, "name");
        return new OperationSpanBuilderImpl(specFactory, traceparentReader, name);
    }

    @Override
    @Nonnull
    public TransportTracing transport() {
        return new DefaultTransportTracing(specFactory, traceparentReader);
    }

    @Override
    @Nonnull
    public SpanExecution fromSpec(@Nonnull SpanSpec spec) {
        return specFactory.fromSpec(spec);
    }

    static OtelTraceparentReader readerFor(@Nonnull TracingRuntime runtime) {
        return switch (runtime.state().mode()) {
            case ENABLED -> OtelTraceparentReaderImpl.INSTANCE;
            case DISABLED_BY_CONFIGURATION, NOOP -> DisabledTraceparentReader.INSTANCE;
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
        public RemoteSpanLink require(@Nonnull String traceparent) {
            throw new IllegalArgumentException("traceparent parsing is disabled");
        }
    }
}
