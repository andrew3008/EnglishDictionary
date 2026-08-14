package space.br1440.platform.tracing.otel.javaagent.resilience;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.diagnostics.TracingDiagnostics;

public final class DegradedModeController {

    public enum Component { SAMPLER, PROCESSOR }

    private final CircuitBreaker samplerBreaker;
    private final CircuitBreaker processorBreaker;
    private final TracingDiagnostics diagnostics;

    public DegradedModeController(TracingDiagnostics diagnostics) {
        this(diagnostics,
                new CircuitBreaker("sampler"),
                new CircuitBreaker("processor")
        );
    }

    DegradedModeController(TracingDiagnostics diagnostics,
                           CircuitBreaker samplerBreaker,
                           CircuitBreaker processorBreaker) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.samplerBreaker = Objects.requireNonNull(samplerBreaker, "samplerBreaker");
        this.processorBreaker = Objects.requireNonNull(processorBreaker, "processorBreaker");
    }

    public CircuitBreaker.State acquireExecutionState(Component component) {
        return breaker(component).acquireExecutionState();
    }

    public void recordSuccess(Component component) {
        breaker(component).recordSuccess();
        refreshAggregate();
    }

    public void recordFailure(Component component) {
        breaker(component).recordFailure();
        refreshAggregate();
    }

    public boolean isDegraded() {
        return (samplerBreaker.getState() != CircuitBreaker.State.CLOSED)
                || (processorBreaker.getState() != CircuitBreaker.State.CLOSED);
    }

    private void refreshAggregate() {
        diagnostics.setDegradedMode(isDegraded());
    }

    private CircuitBreaker breaker(Component component) {
        return (component == Component.SAMPLER) ? samplerBreaker : processorBreaker;
    }
}
