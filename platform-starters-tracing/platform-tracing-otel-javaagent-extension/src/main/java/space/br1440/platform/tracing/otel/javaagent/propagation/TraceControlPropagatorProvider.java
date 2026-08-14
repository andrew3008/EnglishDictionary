package space.br1440.platform.tracing.otel.javaagent.propagation;

import jakarta.annotation.Nonnull;

import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider;

public final class TraceControlPropagatorProvider implements ConfigurablePropagatorProvider {

    static final String NAME = "platform-trace-control";

    @Override
    public TextMapPropagator getPropagator(@Nonnull ConfigProperties config) {
        TraceControlPropagator control = new TraceControlPropagator(
                PropagationDefaults.getForceTraceHeader(config),
                PropagationDefaults.getQaTraceHeader(config),
                PropagationDefaults.getRequestIdHeader(config)
        );

        return new SafeTextMapPropagator(control);
    }

    @Override
    public String getName() {
        return NAME;
    }
}
