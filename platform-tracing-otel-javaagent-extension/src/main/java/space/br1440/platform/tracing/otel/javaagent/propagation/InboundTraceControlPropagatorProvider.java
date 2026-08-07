package space.br1440.platform.tracing.otel.javaagent.propagation;

import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider;

import jakarta.annotation.Nonnull;

public final class InboundTraceControlPropagatorProvider implements ConfigurablePropagatorProvider {

    public static final String NAME = "platform-trace-control";

    @Override
    public TextMapPropagator getPropagator(@Nonnull ConfigProperties config) {
        return InboundTraceControlPropagatorBuilder.build(config);
    }

    @Override
    public String getName() {
        return NAME;
    }
}
