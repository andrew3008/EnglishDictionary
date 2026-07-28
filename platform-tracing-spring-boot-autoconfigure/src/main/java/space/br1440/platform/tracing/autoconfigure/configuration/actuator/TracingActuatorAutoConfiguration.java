package space.br1440.platform.tracing.autoconfigure.configuration.actuator;

import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.tracing.autoconfigure.configuration.AcceptedTracingPolicyReader;
import space.br1440.platform.tracing.autoconfigure.configuration.AcceptedTracingPolicySnapshotReader;
import space.br1440.platform.tracing.autoconfigure.configuration.InProcessAcceptedSnapshotStore;
import space.br1440.platform.tracing.autoconfigure.configuration.PrivateJmxTracingPolicySnapshotClient;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicySnapshotClient;
import space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlLocator;
import space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlOpenTypeCodec;

/**
 * Auto-configuration for P4 unified tracing policy adapters.
 * <p>
 * InProcessAcceptedSnapshotStore is the sole implementation of
 * AcceptedTracingPolicySnapshotReader. No new JMX operations are added.
 * E7/P0 wire contract: 1 mutation (applySnapshot) + 6 marker reads (unchanged).
 * <p>
 * Control write surface (TracingControlMvcController) is registered via
 * TracingControlManagementContextConfiguration which requires both:
 *   platform.tracing.configuration.authority=ACTUATOR
 *   platform.environment=LOCAL
 */
@AutoConfiguration
public class TracingActuatorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public InProcessAcceptedSnapshotStore inProcessAcceptedSnapshotStore() {
        return new InProcessAcceptedSnapshotStore();
    }

    @Bean
    @ConditionalOnMissingBean(PrivateJmxTracingPolicySnapshotClient.class)
    @ConditionalOnBean({PrivateControlLocator.class, PrivateControlOpenTypeCodec.class})
    public PrivateJmxTracingPolicySnapshotClient tracingPolicySnapshotClient(
            PrivateControlLocator locator,
            PrivateControlOpenTypeCodec codec,
            InProcessAcceptedSnapshotStore store) {
        PrivateJmxTracingPolicySnapshotClient client =
                new PrivateJmxTracingPolicySnapshotClient(locator, codec, store);
        client.initialize();
        return client;
    }

    @Bean
    @ConditionalOnBean(PrivateJmxTracingPolicySnapshotClient.class)
    @ConditionalOnMissingBean(TracingPolicySnapshotClient.class)
    public TracingPolicySnapshotClient tracingPolicySnapshotClientAlias(
            PrivateJmxTracingPolicySnapshotClient client) {
        return client;
    }

    @Bean
    @ConditionalOnBean(InProcessAcceptedSnapshotStore.class)
    @ConditionalOnMissingBean(AcceptedTracingPolicySnapshotReader.class)
    public AcceptedTracingPolicySnapshotReader acceptedTracingPolicySnapshotReader(
            InProcessAcceptedSnapshotStore store) {
        return store;
    }

    @Bean
    @ConditionalOnBean(PrivateJmxTracingPolicySnapshotClient.class)
    @ConditionalOnMissingBean(AcceptedTracingPolicyReader.class)
    public AcceptedTracingPolicyReader acceptedTracingPolicyReader(
            PrivateJmxTracingPolicySnapshotClient client) {
        return client;
    }

    @Bean
    @ConditionalOnAvailableEndpoint(endpoint = TracingActuatorEndpoint.class)
    @ConditionalOnMissingBean(TracingActuatorEndpoint.class)
    public TracingActuatorEndpoint tracingActuatorEndpoint(
            AcceptedTracingPolicyReader reader) {
        return new TracingActuatorEndpoint(reader);
    }

    // Actuator adapter: created only when authority=ACTUATOR
    // Physical absence of the controller is enforced by
    // TracingControlManagementContextConfiguration (authority=ACTUATOR AND env=LOCAL)
    @Configuration
    @ConditionalOnProperty(
            name = "platform.tracing.configuration.authority",
            havingValue = "ACTUATOR",
            matchIfMissing = false
    )
    static class ActuatorAdapterConfiguration {

        @Bean
        @ConditionalOnMissingBean(SpringActuatorTracingPolicyAdapter.class)
        public SpringActuatorTracingPolicyAdapter springActuatorTracingPolicyAdapter(
                AcceptedTracingPolicySnapshotReader snapshotReader,
                TracingPolicySnapshotClient snapshotClient) {
            return new SpringActuatorTracingPolicyAdapter(snapshotReader, snapshotClient);
        }
    }
}
