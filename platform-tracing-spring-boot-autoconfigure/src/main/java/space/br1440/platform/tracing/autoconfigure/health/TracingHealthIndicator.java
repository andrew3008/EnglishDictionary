package space.br1440.platform.tracing.autoconfigure.health;

import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.support.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.support.SdkModeDiagnostics;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;

/**
 * Health indicator платформенного модуля трассировки.
 * <p>
 * Возвращает статус {@code UP} при наличии полноценной реализации {@link TraceOperations} и
 * подтверждённого startup-состояния Controlled Agent; статус {@code OUT_OF_SERVICE} при использовании
 * безоперационной заглушки {@link NoopTraceOperations}.
 * <p>
 * Health indicator не отправляет данные в Collector и не влияет на производительность приложения.
 */
public class TracingHealthIndicator extends AbstractHealthIndicator {

    private final TraceOperations traceOperations;
    private final SdkModeDiagnostics diagnostics;

    public TracingHealthIndicator(TraceOperations traceOperations, SdkModeDiagnostics diagnostics) {
        this.traceOperations = traceOperations;
        this.diagnostics = diagnostics;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        boolean noop = traceOperations instanceof NoopTraceOperations;
        boolean ready = diagnostics.runtimeState() == AgentRuntimeState.AGENT_READY;

        builder.withDetail("platformTracingRuntime", traceOperations.getClass().getName());
        builder.withDetail("runtimeState", diagnostics.runtimeState().name());
        builder.withDetail("failureCode", diagnostics.extensionDescriptor().failureCode());

        if (noop || !ready) {
            builder.outOfService();
            builder.withDetail("reason", noop
                    ? "Платформенный фасад работает в безоперационном режиме (NoopTraceOperations)"
                    : "Controlled Agent не подтвердил состояние READY");
            return;
        }

        builder.up();
    }
}
