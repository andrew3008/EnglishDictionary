package space.br1440.platform.tracing.otel.javaagent.propagation;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.configuration.BaggageExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.diagnostics.TracingDiagnostics;

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.context.propagation.TextMapPropagator;

public final class BaggagePropagationCustomizer {

    private final TracingDiagnostics diagnostics;

    public BaggagePropagationCustomizer() {
        this(TracingDiagnostics.shared());
    }

    BaggagePropagationCustomizer(TracingDiagnostics diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public TextMapPropagator apply(TextMapPropagator propagator, BaggageExtensionConfig baggage) {
        if (!(propagator instanceof W3CBaggagePropagator)) {
            return propagator;
        }

        if (!baggage.propagationEnabled()) {
            return TextMapPropagator.noop();
        }

        return new PlatformBaggagePropagator(
                new SafeTextMapPropagator(propagator, diagnostics),
                baggage.outboundHeaderPolicy()
        );
    }
}
