package space.br1440.platform.tracing.otel.extension.propagation;

import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

import java.util.HashSet;

public final class BaggagePropagationCustomizer {

    public TextMapPropagator apply(TextMapPropagator propagator, ConfigProperties config) {
        if (!BaggagePropagatorTypeDetector.isW3cBaggagePropagator(propagator)) {
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

        return new FailClosedCorrelationBaggagePropagator(
                new SafeTextMapPropagator(outboundPolicy)
        );
    }
}
