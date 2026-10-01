package space.br1440.platform.tracing.otel.javaagent.propagation;

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.context.propagation.TextMapPropagator;

import java.util.HashSet;

import space.br1440.platform.tracing.otel.javaagent.configuration.BaggageExtensionConfig;

public final class BaggagePropagationCustomizer {

    public TextMapPropagator apply(TextMapPropagator propagator, BaggageExtensionConfig baggage) {
        if (!(propagator instanceof W3CBaggagePropagator)) {
            return propagator;
        }

        TextMapPropagator outboundPolicy = propagator;
        if (baggage.propagationEnabled()) {
            outboundPolicy = new FilteringBaggagePropagator(
                    propagator,
                    new HashSet<>(baggage.outboundHeaderAllowedKeys()),
                    baggage.outboundHeaderDenyPatterns()
            );
        }

        SafeTextMapPropagator safePropagator = new SafeTextMapPropagator(outboundPolicy);
        return new FailClosedCorrelationBaggagePropagator(safePropagator);
    }
}
