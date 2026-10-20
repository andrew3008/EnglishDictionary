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

        if (!baggage.propagationEnabled()) {
            return TextMapPropagator.noop();
        }

        FilteringBaggagePropagator filtered = new FilteringBaggagePropagator(
                propagator,
                new HashSet<>(baggage.outboundHeaderAllowedKeys()),
                baggage.outboundHeaderDenyPatterns()
        );
        return new FailClosedCorrelationBaggagePropagator(new SafeTextMapPropagator(filtered));
    }
}
