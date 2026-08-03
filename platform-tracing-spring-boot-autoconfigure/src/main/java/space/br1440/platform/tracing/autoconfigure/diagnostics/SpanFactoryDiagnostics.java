package space.br1440.platform.tracing.autoconfigure.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.state.TracingRuntimeMode;
import space.br1440.platform.tracing.otel.runtime.state.TracingState;

public final class SpanFactoryDiagnostics {

    private final TracingRuntime tracingRuntime;

    public SpanFactoryDiagnostics(@Nonnull TracingRuntime tracingRuntime) {
        this.tracingRuntime = Objects.requireNonNull(tracingRuntime, "tracingRuntime");
    }

    @Nonnull
    public Map<String, Object> toActuatorMap() {
        TracingState state = tracingRuntime.state();

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mode", toPublicMode(state.mode()));

        state.reason().ifPresent(reason -> map.put("reason", reason));
        map.put("details", state.details());

        return map;
    }

    @Nonnull
    private static String toPublicMode(@Nonnull TracingRuntimeMode internalMode) {
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
