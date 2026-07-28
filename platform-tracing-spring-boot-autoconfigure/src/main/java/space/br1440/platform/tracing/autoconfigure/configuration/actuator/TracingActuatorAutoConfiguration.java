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
import space.br1440.platform.tracing.autoconfigure.configuration.PrivateJmxTracingPolicySnapshotClient;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicySnapshotClient;
import space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlLocator;
import space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlOpenTypeCodec;

/**
 * Auto-configuration for P4 unified tracing policy adapters.
 * <p>
 * Single PrivateJmxTracingPolicySnapshotClient implements all three capability interfaces.
 * Spring wires them separately to different consumers.
 * <p>
 * Control endpoint (tracingControl): registered ONLY when:
 *   authority = ACTUATOR
 *   AND platform.environment = LOCAL (resolved and validated by guard at startup)
 * dev-control.enabled flag is removed; authority=ACTUATOR is the activation signal.
 */
@AutoConfiguration
public class TracingActuatorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(PrivateJmxTracingPolicySnapshotClient.class)
    @ConditionalOnBean({PrivateControlLocator.class, PrivateControlOpenTypeCodec.class})
    public PrivateJmxTracingPolicySnapshotClient tracingPolicySnapshotClient(
            PrivateControlLocator locator,
            PrivateControlOpenTypeCodec codec) {
        PrivateJmxTracingPolicySnapshotClient client = new PrivateJmxTracingPolicySnapshotClient(locator, codec);
        client.initialize();
        return client;
    }

    // Expose individual capability interfaces from the single client bean
    @Bean
    @ConditionalOnBean(PrivateJmxTracingPolicySnapshotClient.class)
    @ConditionalOnMissingBean(TracingPolicySnapshotClient.class)
    public TracingPolicySnapshotClient tracingPolicySnapshotClientAlias(
            PrivateJmxTracingPolicySnapshotClient client) {
        return client;
    }

    @Bean
    @ConditionalOnBean(PrivateJmxTracingPolicySnapshotClient.class)
    @ConditionalOnMissingBean(AcceptedTracingPolicySnapshotReader.class)
    public AcceptedTracingPolicySnapshotReader acceptedTracingPolicySnapshotReader(
            PrivateJmxTracingPolicySnapshotClient client) {
        return client;
    }

    @Bean
    @ConditionalOnBean(PrivateJmxTracingPolicySnapshotClient.class)
    @ConditionalOnMissingBean(AcceptedTracingPolicyReader.class)
    public AcceptedTracingPolicyReader acceptedTracingPolicyReader(
            PrivateJmxTracingPolicySnapshotClient client) {
        return client;
    }

    // Read-only endpoint: technology-neutral @Endpoint (read via JMX is acceptable)
    @Bean
    @ConditionalOnAvailableEndpoint(endpoint = TracingActuatorEndpoint.class)
    @ConditionalOnMissingBean(TracingActuatorEndpoint.class)
    public TracingActuatorEndpoint tracingActuatorEndpoint(AcceptedTracingPolicyReader reader) {
        return new TracingActuatorEndpoint(reader);
    }

    // Control write surface: HTTP-only @WebEndpoint
    // Physically absent unless authority=ACTUATOR AND environment=LOCAL
    // No dev-control.enabled: authority=ACTUATOR is the sole activation signal
    @Configuration
    @ConditionalOnProperty(
            name = "platform.tracing.configuration.authority",
            havingValue = "ACTUATOR"
    )
    static class ActuatorControlConfiguration {

        @Bean
        @ConditionalOnMissingBean(SpringActuatorTracingPolicyAdapter.class)
        public SpringActuatorTracingPolicyAdapter springActuatorTracingPolicyAdapter(
                AcceptedTracingPolicySnapshotReader snapshotReader,
                TracingPolicySnapshotClient snapshotClient) {
            return new SpringActuatorTracingPolicyAdapter(snapshotReader, snapshotClient);
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
