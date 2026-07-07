package space.br1440.platform.tracing.autoconfigure.diagnostics;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.core.impl.TracingImplementation;

import java.util.Map;
import java.util.Objects;

/**
 * Actuator-facing diagnostics for manual platform tracing (Slice 7).
 * <p>
 * Distinct from {@code platform-tracing-otel-extension} safety counters named
 * {@code TracingDiagnostics}.
 */
public final class ManualTracingDiagnostics {

    private final TracingImplementation tracingImplementation;

    public ManualTracingDiagnostics(@Nonnull TracingImplementation tracingImplementation) {
        this.tracingImplementation = Objects.requireNonNull(tracingImplementation, "tracingImplementation");
    }

    @Nonnull
    public TracingDiagnosticsView view() {
        return TracingDiagnosticsMapper.fromState(tracingImplementation.state());
    }

    @Nonnull
    public Map<String, Object> toActuatorMap() {
        return view().toActuatorMap();
    }
}
