package space.br1440.platform.tracing.otel.javaagent.propagation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import space.br1440.platform.tracing.otel.javaagent.configuration.OtelSdkPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.configuration.PropagatorConfigurationContract;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

public final class PlatformPropagatorsDefaultsCustomizer implements Function<ConfigProperties, Map<String, String>> {

    static final List<String> OTEL_DEFAULT_PROPAGATORS = List.of("tracecontext", "baggage");

    @Override
    public Map<String, String> apply(ConfigProperties config) {
        List<String> propagators = config.getList(OtelSdkPropertyNames.PROPAGATORS);
        if (propagators.isEmpty()) {
            propagators = OTEL_DEFAULT_PROPAGATORS;
        }

        if (propagators.contains(PropagatorConfigurationContract.NONE)) {
            return Map.of();
        }

        if (propagators.contains(PropagatorConfigurationContract.PLATFORM_PROPAGATOR)) {
            return Map.of();
        }

        List<String> updated = new ArrayList<>(propagators);
        updated.add(PropagatorConfigurationContract.PLATFORM_PROPAGATOR);
        return Map.of(OtelSdkPropertyNames.PROPAGATORS, String.join(",", updated));
    }
}
