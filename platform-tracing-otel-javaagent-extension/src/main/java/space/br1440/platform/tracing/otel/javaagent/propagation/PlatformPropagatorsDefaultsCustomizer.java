package space.br1440.platform.tracing.otel.javaagent.propagation;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class PlatformPropagatorsDefaultsCustomizer implements Function<ConfigProperties, Map<String, String>> {

    static final String PROPAGATORS_KEY = "otel.propagators";
    static final String PLATFORM_PROPAGATOR = TraceControlPropagatorProvider.NAME;
    static final String NONE = "none";
    static final List<String> OTEL_DEFAULT_PROPAGATORS = List.of("tracecontext", "baggage");

    @Override
    public Map<String, String> apply(ConfigProperties config) {
        List<String> propagators = config.getList(PROPAGATORS_KEY);
        if (propagators.isEmpty()) {
            propagators = OTEL_DEFAULT_PROPAGATORS;
        }

        if (propagators.contains(NONE)) {
            return Map.of();
        }

        if (propagators.contains(PLATFORM_PROPAGATOR)) {
            return Map.of();
        }

        List<String> updated = new ArrayList<>(propagators);
        updated.add(PLATFORM_PROPAGATOR);
        return Map.of(PROPAGATORS_KEY, String.join(",", updated));
    }
}
