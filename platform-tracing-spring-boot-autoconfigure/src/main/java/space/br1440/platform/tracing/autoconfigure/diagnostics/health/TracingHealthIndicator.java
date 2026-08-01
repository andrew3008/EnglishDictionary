package space.br1440.platform.tracing.autoconfigure.diagnostics.health;

import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.state.TracingRuntimeMode;
import space.br1440.platform.tracing.otel.runtime.state.TracingState;

/**
 * Health indicator платформенного модуля трассировки.
 * <p>
 * Возвращает статус {@code UP} при включённом платформенном runtime и подтверждённом
 * startup-состоянии Controlled Agent; статус {@code OUT_OF_SERVICE} при намеренно отключённом
 * tracing ({@code platform.tracing.enabled=false}) или недоступном runtime.
 * <p>
 * Health indicator не отправляет данные в Collector и не влияет на производительность приложения.
 */
public class TracingHealthIndicator extends AbstractHealthIndicator {

    private final TraceOperations traceOperations;
    private final TracingRuntime tracingRuntime;
    private final TracingDeploymentDiagnostics diagnostics;

    public TracingHealthIndicator(
            TraceOperations traceOperations,
            TracingRuntime tracingRuntime,
            TracingDeploymentDiagnostics diagnostics) {
        this.traceOperations = traceOperations;
        this.tracingRuntime = tracingRuntime;
        this.diagnostics = diagnostics;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        TracingState tracingState = tracingRuntime.state();
        boolean operational = tracingState.mode() == TracingRuntimeMode.ENABLED;
        boolean ready = diagnostics.runtimeState() == AgentRuntimeState.AGENT_READY;

        builder.withDetail("platformTracingRuntime", traceOperations.getClass().getName());
        builder.withDetail("platformRuntimeMode", tracingState.mode().name());
        builder.withDetail("tracingEnabled", diagnostics.tracingEnabled());
        builder.withDetail("agentState", diagnostics.runtimeState().name());
        builder.withDetail("failureCode", diagnostics.extensionDescriptor().failureCode());

        if (!operational || !ready) {
            builder.outOfService();
            builder.withDetail("reason", !operational
                    ? tracingState.reason().orElse("Платформенный runtime недоступен")
                    : "Controlled Agent не подтвердил состояние READY");
            return;
        }

        builder.up();
    }
}
