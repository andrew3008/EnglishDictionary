package space.br1440.platform.tracing.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.actuator.LegacyTracingDiagnosticsEndpoint;
import space.br1440.platform.tracing.autoconfigure.diagnostics.SpanFactoryDiagnostics;
import space.br1440.platform.tracing.autoconfigure.health.TracingHealthIndicator;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.support.SdkModeDiagnostics;

/**
 * Авто-конфигурация Actuator-интеграций платформенного модуля трассировки.
 * <p>
 * Регистрирует health indicator {@code tracing} и endpoint {@code /actuator/tracing}.
 * Активна при наличии в classpath инфраструктуры Actuator.
 */
@AutoConfiguration
@AutoConfigureAfter(TracingCoreAutoConfiguration.class)
@ConditionalOnClass({Endpoint.class, HealthIndicator.class})
@ConditionalOnProperty(prefix = TracingProperties.PREFIX, name = "mode", havingValue = "AGENT", matchIfMissing = true)
public class TracingDiagnosticsActuatorAutoConfiguration {

    @Bean(name = "tracingHealthIndicator")
    @ConditionalOnMissingBean(name = "tracingHealthIndicator")
    @ConditionalOnEnabledHealthIndicator("tracing")
    public TracingHealthIndicator tracingHealthIndicator(
            TraceOperations traceOperations,
            SdkModeDiagnostics sdkModeDiagnostics) {
        return new TracingHealthIndicator(traceOperations, sdkModeDiagnostics);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnAvailableEndpoint
    public LegacyTracingDiagnosticsEndpoint tracingDiagnosticsEndpoint(TraceOperations traceOperations,
                                                            TracingProperties properties,
                                                            PlatformTracingJmxClient platformTracingJmxClient,
                                                            ObjectProvider<SdkModeDiagnostics> sdkModeDiagnostics,
                                                            SpanFactoryDiagnostics spanFactoryDiagnostics,
                                                            Environment environment) {
        return new LegacyTracingDiagnosticsEndpoint(traceOperations, properties, platformTracingJmxClient,
                sdkModeDiagnostics.getIfAvailable(), spanFactoryDiagnostics, environment);
    }
}
