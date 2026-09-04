package space.br1440.platform.tracing.autoconfigure.control.management;

import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import space.br1440.platform.tracing.autoconfigure.control.jmx.PlatformJmxControlAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsApplier;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.CompleteTracingSettingsReader;

@AutoConfiguration
@AutoConfigureAfter(PlatformJmxControlAutoConfiguration.class)
public class TracingSettingsControlAutoConfiguration {

    @Configuration
    @ConditionalOnClass(WebEndpoint.class)
    @ConditionalOnProperty(name = "platform.tracing.configuration.authority", havingValue = "ACTUATOR")
    @ConditionalOnProperty(name = "platform.environment", havingValue = "LOCAL")
    static class ActuatorAdapterConfiguration {

        @Bean
        @ConditionalOnMissingBean(TracingSettingsMutationService.class)
        public TracingSettingsMutationService springActuatorTracingSettingsAdapter(CompleteTracingSettingsReader snapshotReader,
                                                                                   TracingSettingsApplier snapshotClient) {
            return new TracingSettingsMutationService(snapshotReader, snapshotClient);
        }

        @Bean
        @ConditionalOnMissingBean(TracingControlEndpoint.class)
        public TracingControlEndpoint tracingControlEndpoint(TracingSettingsMutationService mutationService) {
            return new TracingControlEndpoint(mutationService);
        }

        @Bean
        public ActuatorAgentReadinessProbe actuatorAgentReadinessProbe(CompleteTracingSettingsReader snapshotReader) {
            return new ActuatorAgentReadinessProbe(snapshotReader);
        }
    }
}
