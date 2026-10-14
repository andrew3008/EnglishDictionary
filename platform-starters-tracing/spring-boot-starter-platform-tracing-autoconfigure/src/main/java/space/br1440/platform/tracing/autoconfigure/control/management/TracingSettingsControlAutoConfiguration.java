package space.br1440.platform.tracing.autoconfigure.control.management;

import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingControlChannelCondition;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PlatformJmxControlAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsWriter;

@AutoConfiguration
@AutoConfigureAfter(PlatformJmxControlAutoConfiguration.class)
public class TracingSettingsControlAutoConfiguration {

    @Configuration
    @ConditionalOnClass(WebEndpoint.class)
    @Conditional(TracingControlChannelCondition.OnActuator.class)
    static class ActuatorAdapterConfiguration {

        @Bean
        public TracingSettingsMutationService springActuatorTracingSettingsAdapter(TracingSettingsWriter settingsWriter) {
            return new TracingSettingsMutationService(settingsWriter);
        }

        @Bean
        public TracingControlEndpoint tracingControlEndpoint(TracingSettingsMutationService mutationService) {
            return new TracingControlEndpoint(mutationService);
        }

        @Bean
        public ActuatorAgentReadinessProbe actuatorAgentReadinessProbe(TracingSettingsWriter settingsWriter) {
            return new ActuatorAgentReadinessProbe(settingsWriter);
        }
    }
}
