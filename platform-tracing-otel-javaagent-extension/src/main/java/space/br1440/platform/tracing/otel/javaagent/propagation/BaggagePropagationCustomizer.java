package space.br1440.platform.tracing.otel.javaagent.propagation;

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

import java.util.HashSet;

public final class BaggagePropagationCustomizer {

    public TextMapPropagator apply(TextMapPropagator propagator, ConfigProperties config) {
        if (!(propagator instanceof W3CBaggagePropagator)) {
            return propagator;
        }

        TextMapPropagator outboundPolicy = propagator;
        if (PropagationDefaults.isBaggageEnabled(config)) {
            outboundPolicy = new FilteringBaggagePropagator(
                    propagator,
                    new HashSet<>(PropagationDefaults.getBaggageAllowedKeys(config)),
                    PropagationDefaults.getBaggageDenyPatterns(config)
            );
        }

        SafeTextMapPropagator safePropagator = new SafeTextMapPropagator(outboundPolicy);
        return new FailClosedCorrelationBaggagePropagator(safePropagator);
    }
}
