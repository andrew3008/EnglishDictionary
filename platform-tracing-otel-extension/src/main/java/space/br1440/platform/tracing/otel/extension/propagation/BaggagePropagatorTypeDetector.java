package space.br1440.platform.tracing.otel.extension.propagation;

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.context.propagation.TextMapPropagator;
import lombok.experimental.UtilityClass;

@UtilityClass
final class BaggagePropagatorTypeDetector {

    static boolean isW3cBaggagePropagator(TextMapPropagator propagator) {
        return propagator instanceof W3CBaggagePropagator;
    }
}
