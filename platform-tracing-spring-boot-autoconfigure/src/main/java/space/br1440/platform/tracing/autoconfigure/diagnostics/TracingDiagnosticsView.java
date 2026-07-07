package space.br1440.platform.tracing.autoconfigure.diagnostics;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Stable Actuator/support diagnostics contract for manual platform tracing (Slice 7).
 * <p>
 * Must not expose internal {@code TracingState}, {@code TracingMode}, or OpenTelemetry SDK types.
 */
public record TracingDiagnosticsView(
        @Nonnull String mode,
        @Nullable String reason,
        @Nonnull Map<String, String> details) {

    public TracingDiagnosticsView {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(details, "details");
        details = Map.copyOf(details);
    }

    /**
     * Actuator-safe map with stable top-level keys {@code mode}, {@code reason}, {@code details}.
     */
    @Nonnull
    public Map<String, Object> toActuatorMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mode", mode);
        if (reason != null) {
            map.put("reason", reason);
        }
        map.put("details", details);
        return map;
    }
}
