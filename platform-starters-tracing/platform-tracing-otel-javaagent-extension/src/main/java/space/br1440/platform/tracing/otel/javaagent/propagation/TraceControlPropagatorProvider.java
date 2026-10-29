package space.br1440.platform.tracing.otel.javaagent.propagation;

import java.util.Objects;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.otel.javaagent.configuration.PropagatorConfigurationContract;

import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider;

public final class TraceControlPropagatorProvider implements ConfigurablePropagatorProvider {

    static final String NAME = PropagatorConfigurationContract.PLATFORM_PROPAGATOR;

    @Override
    public TextMapPropagator getPropagator(@Nonnull ConfigProperties config) {
        Objects.requireNonNull(config, "config");
        return new SafeTextMapPropagator(new TraceControlPropagator());
    }

    @Override
    public String getName() {
        return NAME;
    }
}
