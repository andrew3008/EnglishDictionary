package space.br1440.platform.tracing.autoconfigure.diagnostics;

import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;

import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapPropertyNames;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.diagnostics.health.TracingHealthIndicator;
import space.br1440.platform.tracing.autoconfigure.agent.TracingModeDiagnostics;

/**
 * Авто-конфигурация Actuator-интеграций платформенного модуля трассировки.
 * <p>
 * Регистрирует health indicator {@code tracing}. Единый read-model публикуется отдельно
 * через {@code tracingStatus}, в том числе для режима DISABLED.
 * Активна при наличии в classpath инфраструктуры Actuator.
 */
@AutoConfiguration
@AutoConfigureAfter(TracingRuntimeAutoConfiguration.class)
@ConditionalOnClass({Endpoint.class, HealthIndicator.class})
@ConditionalOnProperty(name = TracingBootstrapPropertyNames.MODE, havingValue = "AGENT", matchIfMissing = true)
public class TracingDiagnosticsActuatorAutoConfiguration {

    @Bean(name = "tracingHealthIndicator")
    @ConditionalOnMissingBean(name = "tracingHealthIndicator")
    @ConditionalOnEnabledHealthIndicator("tracing")
    public TracingHealthIndicator tracingHealthIndicator(
            TraceOperations traceOperations,
            TracingModeDiagnostics sdkModeDiagnostics) {
        return new TracingHealthIndicator(traceOperations, sdkModeDiagnostics);
    }
}
