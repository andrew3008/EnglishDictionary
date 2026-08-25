package space.br1440.platform.tracing.autoconfigure.control.jmx;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

@AutoConfiguration
@Conditional(PrivateJmxControlAutoConfiguration.PrivateJmxControlAuthorityCondition.class)
public class PrivateJmxControlAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(PrivateControlLocator.class)
    public PrivateControlLocator privateControlLocator() {
        return new PrivateControlLocator();
    }

    @Bean
    @ConditionalOnMissingBean(PrivateJmxTracingSettingsClient.class)
    @ConditionalOnBean(PrivateControlLocator.class)
    public PrivateJmxTracingSettingsClient tracingSettingsSnapshotClient(PrivateControlLocator locator) {
        PrivateJmxTracingSettingsClient client = new PrivateJmxTracingSettingsClient(locator);
        client.initialize();
        return client;
    }

    static final class PrivateJmxControlAuthorityCondition extends AnyNestedCondition {

        PrivateJmxControlAuthorityCondition() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(
                name = "platform.tracing.configuration.authority",
                havingValue = "ACTUATOR"
        )
        @SuppressWarnings("unused")
        static class OnActuator {
        }

        @ConditionalOnProperty(
                name = "platform.tracing.configuration.authority",
                havingValue = "CONFIG_SERVER"
        )
        @SuppressWarnings("unused")
        static class OnConfigServer {
        }
    }
}
