package space.br1440.platform.tracing.autoconfigure.diagnostics;

import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.autoconfigure.diagnostics.health.TracingHealthIndicator;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

@AutoConfiguration
@AutoConfigureAfter(TracingRuntimeAutoConfiguration.class)
@ConditionalOnClass({Endpoint.class, HealthIndicator.class})
@ConditionalOnBean({TraceOperations.class, TracingRuntime.class, TracingDeploymentDiagnostics.class})
public class TracingDiagnosticsActuatorAutoConfiguration {

    @Bean(name = "tracingHealthIndicator")
    @ConditionalOnMissingBean(name = "tracingHealthIndicator")
    @ConditionalOnEnabledHealthIndicator("tracing")
    public TracingHealthIndicator tracingHealthIndicator(TraceOperations traceOperations,
                                                         TracingRuntime tracingRuntime,
                                                         TracingDeploymentDiagnostics deploymentDiagnostics) {
        return new TracingHealthIndicator(traceOperations, tracingRuntime, deploymentDiagnostics);
    }
}
