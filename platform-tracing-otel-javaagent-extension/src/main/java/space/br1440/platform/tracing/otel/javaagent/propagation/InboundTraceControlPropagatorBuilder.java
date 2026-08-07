package space.br1440.platform.tracing.otel.javaagent.propagation;

import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import lombok.experimental.UtilityClass;

@UtilityClass
public final class InboundTraceControlPropagatorBuilder {

    /** Строит safe-обёрнутый {@link InboundTraceControlPropagator} из {@link ConfigProperties}. */
    public static TextMapPropagator build(ConfigProperties config) {
        InboundTraceControlPropagator control = new InboundTraceControlPropagator(
                PropagationDefaults.getForceTraceHeader(config),
                PropagationDefaults.getQaTraceHeader(config),
                PropagationDefaults.getRequestIdHeader(config)
        );
        return new SafeTextMapPropagator(control);
    }
}
