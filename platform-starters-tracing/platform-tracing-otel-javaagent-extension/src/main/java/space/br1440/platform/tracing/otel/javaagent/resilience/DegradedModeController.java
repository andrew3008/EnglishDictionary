package space.br1440.platform.tracing.otel.javaagent.resilience;

import java.time.Duration;
import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.diagnostics.TracingDiagnostics;

public final class DegradedModeController {

    private static final int FAILURE_THRESHOLD = 5;
    private static final Duration COOLDOWN = Duration.ofSeconds(60);

    private final CircuitBreaker breaker;
    private final TracingDiagnostics diagnostics;

    public DegradedModeController(TracingDiagnostics diagnostics) {
        this(diagnostics, new CircuitBreaker(FAILURE_THRESHOLD, COOLDOWN));
    }

    DegradedModeController(TracingDiagnostics diagnostics, CircuitBreaker breaker) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.breaker = Objects.requireNonNull(breaker, "breaker");
    }

    public CircuitBreaker.Admission tryAcquire() {
        return breaker.tryAcquire();
    }

    public void recordSuccess(CircuitBreaker.Admission admission) {
        breaker.recordSuccess(admission);
        publishDegradedMode();
    }

    public void recordFailure(CircuitBreaker.Admission admission) {
        breaker.recordFailure(admission);
        publishDegradedMode();
    }

    public boolean isDegraded() {
        return breaker.state() != CircuitBreaker.State.CLOSED;
    }

    private void publishDegradedMode() {
        boolean degraded = isDegraded();
        if (diagnostics.isDegradedMode() != degraded) {
            diagnostics.setDegradedMode(degraded);
        }
    }
}
