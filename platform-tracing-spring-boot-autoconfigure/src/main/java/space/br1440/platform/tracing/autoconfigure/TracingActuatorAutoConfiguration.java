package space.br1440.platform.tracing.autoconfigure;

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
import org.springframework.beans.factory.ObjectProvider;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.autoconfigure.actuator.TracingActuatorEndpoint;
import space.br1440.platform.tracing.autoconfigure.diagnostics.ManualTracingDiagnostics;
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
@ConditionalOnProperty(prefix = TracingProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
public class TracingActuatorAutoConfiguration {

    @Bean(name = "tracingHealthIndicator")
    @ConditionalOnMissingBean(name = "tracingHealthIndicator")
    @ConditionalOnEnabledHealthIndicator("tracing")
    public TracingHealthIndicator tracingHealthIndicator(PlatformTracing platformTracing) {
        return new TracingHealthIndicator(platformTracing);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnAvailableEndpoint
    public TracingActuatorEndpoint tracingActuatorEndpoint(PlatformTracing platformTracing,
                                                            TracingProperties properties,
                                                            PlatformTracingJmxClient platformTracingJmxClient,
                                                            ObjectProvider<SdkModeDiagnostics> sdkModeDiagnostics,
                                                            ManualTracingDiagnostics manualTracingDiagnostics) {
        return new TracingActuatorEndpoint(platformTracing, properties, platformTracingJmxClient,
                sdkModeDiagnostics.getIfAvailable(), manualTracingDiagnostics);
    }
}
