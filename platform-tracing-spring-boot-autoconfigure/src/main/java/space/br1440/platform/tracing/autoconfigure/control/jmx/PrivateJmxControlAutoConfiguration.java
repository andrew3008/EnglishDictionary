package space.br1440.platform.tracing.autoconfigure.control.jmx;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PrivateControlLocator;
import space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.PrivateControlOpenTypeCodec;

/**
 * Shared private JMX control-plane wiring for {@code ACTUATOR} and {@code CONFIG_SERVER}
 * authorities. {@code HELM} and {@code DISABLED} never load this configuration.
 */
@AutoConfiguration
@Conditional(PrivateJmxControlAutoConfiguration.PrivateJmxControlAuthorityCondition.class)
public class PrivateJmxControlAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(PrivateControlLocator.class)
    public PrivateControlLocator privateControlLocator() {
        return new PrivateControlLocator();
    }

    @Bean
    @ConditionalOnMissingBean(PrivateControlOpenTypeCodec.class)
    public PrivateControlOpenTypeCodec privateControlOpenTypeCodec() {
        return new PrivateControlOpenTypeCodec();
    }

    @Bean
    @ConditionalOnMissingBean(PrivateJmxTracingPolicySnapshotClient.class)
    @ConditionalOnBean({PrivateControlLocator.class, PrivateControlOpenTypeCodec.class})
    public PrivateJmxTracingPolicySnapshotClient tracingPolicySnapshotClient(
            PrivateControlLocator locator,
            PrivateControlOpenTypeCodec codec) {
        PrivateJmxTracingPolicySnapshotClient client =
                new PrivateJmxTracingPolicySnapshotClient(locator, codec);
        client.initialize();
        return client;
    }

    static final class PrivateJmxControlAuthorityCondition extends AnyNestedCondition {

        PrivateJmxControlAuthorityCondition() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(
                name = "platform.tracing.configuration.authority",
                havingValue = "ACTUATOR",
                matchIfMissing = false)
        static class OnActuator {
        }

        @ConditionalOnProperty(
                name = "platform.tracing.configuration.authority",
                havingValue = "CONFIG_SERVER",
                matchIfMissing = false)
        static class OnConfigServer {
        }
    }
}
