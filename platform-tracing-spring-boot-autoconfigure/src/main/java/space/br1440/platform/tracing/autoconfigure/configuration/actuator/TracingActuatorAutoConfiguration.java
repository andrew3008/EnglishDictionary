package space.br1440.platform.tracing.autoconfigure.configuration.actuator;

import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.tracing.autoconfigure.configuration.AcceptedTracingPolicyReader;
import space.br1440.platform.tracing.autoconfigure.configuration.PrivateJmxTracingPolicySnapshotClient;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicySnapshotClient;
import space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlLocator;
import space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlOpenTypeCodec;

/**
 * Auto-configuration for P4 unified tracing policy adapters.
 * <p>
 * Read endpoint (tracingPolicy): always registered when tracing is enabled.
 * Control endpoint (tracingControl): registered ONLY when authority=ACTUATOR + LOCAL + dev-control.enabled=true.
 * The control endpoint bean is physically absent in all other configurations.
 */
@AutoConfiguration
public class TracingActuatorAutoConfiguration {

    /**
     * Single private JMX snapshot client. Both Config Server and Actuator adapters share this bean.
     */
    @Bean
    @ConditionalOnMissingBean(TracingPolicySnapshotClient.class)
    @ConditionalOnBean({PrivateControlLocator.class, PrivateControlOpenTypeCodec.class})
    public PrivateJmxTracingPolicySnapshotClient tracingPolicySnapshotClient(
            PrivateControlLocator locator,
            PrivateControlOpenTypeCodec codec) {
        PrivateJmxTracingPolicySnapshotClient client = new PrivateJmxTracingPolicySnapshotClient(locator, codec);
        client.initialize();
        return client;
    }

    /**
     * Read-only Actuator endpoint. No write dependency. Depends only on AcceptedTracingPolicyReader.
     */
    @Bean
    @ConditionalOnAvailableEndpoint(endpoint = TracingActuatorEndpoint.class)
    @ConditionalOnMissingBean(TracingActuatorEndpoint.class)
    public TracingActuatorEndpoint tracingActuatorEndpoint(AcceptedTracingPolicyReader reader) {
        return new TracingActuatorEndpoint(reader);
    }

    /**
     * Control write surface. Physically absent unless:
     * - authority = ACTUATOR
     * - platform.environment resolves to LOCAL
     * - platform.tracing.dev-control.enabled = true
     *
     * All three conditions are validated by {@link space.br1440.platform.tracing.autoconfigure.configuration.TracingConfigurationAuthorityGuard}
     * at startup (fail-fast). This bean condition provides physical absence guarantee.
     */
    @Configuration
    @ConditionalOnProperty(
            name = "platform.tracing.configuration.authority",
            havingValue = "ACTUATOR"
    )
    @ConditionalOnProperty(
            name = "platform.tracing.dev-control.enabled",
            havingValue = "true"
    )
    static class ActuatorControlConfiguration {

        @Bean
        @ConditionalOnMissingBean(SpringActuatorTracingPolicyAdapter.class)
        public SpringActuatorTracingPolicyAdapter springActuatorTracingPolicyAdapter(
                TracingPolicySnapshotClient snapshotClient) {
            return new SpringActuatorTracingPolicyAdapter(snapshotClient);
        }

        @Bean
        @ConditionalOnAvailableEndpoint(endpoint = TracingActuatorControlEndpoint.class)
        @ConditionalOnMissingBean(TracingActuatorControlEndpoint.class)
        public TracingActuatorControlEndpoint tracingActuatorControlEndpoint(
                SpringActuatorTracingPolicyAdapter adapter) {
            return new TracingActuatorControlEndpoint(adapter);
        }
    }
}
