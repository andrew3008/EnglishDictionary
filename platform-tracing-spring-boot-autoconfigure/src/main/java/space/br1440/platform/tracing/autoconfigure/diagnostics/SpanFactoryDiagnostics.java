package space.br1440.platform.tracing.autoconfigure.diagnostics;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.state.TracingMode;
import space.br1440.platform.tracing.otel.runtime.state.TracingState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Actuator-facing diagnostics for manual platform tracing (Slice 7).
 * <p>
 * Distinct from {@code platform-tracing-otel-javaagent-extension} safety counters named
 * {@code TracingDiagnostics}.
 */
public final class SpanFactoryDiagnostics {

    private final TracingRuntime tracingImplementation;

    public SpanFactoryDiagnostics(@Nonnull TracingRuntime tracingImplementation) {
        this.tracingImplementation = Objects.requireNonNull(tracingImplementation, "tracingImplementation");
    }

    @Nonnull
    public Map<String, Object> toActuatorMap() {
        TracingState state = tracingImplementation.state();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mode", toPublicMode(state.mode()));
        state.reason().ifPresent(reason -> map.put("reason", reason));
        map.put("details", state.details());
        return map;
    }

    @Nonnull
    private static String toPublicMode(@Nonnull TracingMode internalMode) {
        Objects.requireNonNull(internalMode, "internalMode");
        return switch (internalMode) {
            case ENABLED -> "ENABLED";
            case DISABLED_BY_CONFIGURATION -> "DISABLED_BY_CONFIGURATION";
            case UNAVAILABLE -> "UNAVAILABLE";
            case NOOP -> "NOOP";
            case TEST -> "UNKNOWN";
        };
    }
}
