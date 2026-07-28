package space.br1440.platform.tracing.autoconfigure.configuration.actuator;

import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import space.br1440.platform.tracing.autoconfigure.configuration.AcceptedTracingPolicyReader;
import space.br1440.platform.tracing.autoconfigure.configuration.AcceptedTracingPolicySnapshotReader;
import space.br1440.platform.tracing.autoconfigure.configuration.PrivateJmxTracingPolicySnapshotClient;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicySnapshotClient;
import space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlLocator;
import space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlOpenTypeCodec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Auto-configuration for P4 unified tracing policy adapters.
 * <p>
 * PROPOSED_PRIV_R07 (Variant A): PrivateJmxTracingPolicySnapshotClient calls
 * {@code readAcceptedComplete} (one bounded internal read, proposed for E7 contract).
 * No InProcessAcceptedSnapshotStore — Agent is the sole accepted-state owner.
 * <p>
 * TODO (Agent team): implement AggregatePolicyControlMBean.readAcceptedComplete()
 * and expose correct MBeanInfo / MBeanOperationInfo before P4 can be end-to-end green.
 * Until then every call to AcceptedTracingPolicySnapshotReader.currentComplete()
 * throws TracingPolicyContractViolationException (ReflectionException wrapping).
 * P4 is BLOCKED on that Agent PR.
 * <p>
 * TracingControlMvcController registered via TracingControlManagementContextConfiguration
 * (discovered through ManagementContextConfiguration.imports).
 * Registration requires BOTH: authority=ACTUATOR AND platform.environment=LOCAL.
 */
@AutoConfiguration
public class TracingActuatorAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TracingActuatorAutoConfiguration.class);

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

    @Bean
    @ConditionalOnAvailableEndpoint(endpoint = TracingActuatorEndpoint.class)
    @ConditionalOnMissingBean(TracingActuatorEndpoint.class)
    public TracingActuatorEndpoint tracingActuatorEndpoint(
            AcceptedTracingPolicyReader reader) {
        return new TracingActuatorEndpoint(reader);
    }

    /**
     * Actuator adapter: created only when authority=ACTUATOR.
     * Physical absence of the controller enforced separately by
     * TracingControlManagementContextConfiguration (authority=ACTUATOR AND env=LOCAL).
     */
    @Configuration
    @ConditionalOnProperty(
            name = "platform.tracing.configuration.authority",
            havingValue = "ACTUATOR",
            matchIfMissing = false)
    static class ActuatorAdapterConfiguration {

        @Bean
        @ConditionalOnMissingBean(SpringActuatorTracingPolicyAdapter.class)
        public SpringActuatorTracingPolicyAdapter springActuatorTracingPolicyAdapter(
                AcceptedTracingPolicySnapshotReader snapshotReader,
                TracingPolicySnapshotClient snapshotClient) {
            return new SpringActuatorTracingPolicyAdapter(snapshotReader, snapshotClient);
        }

        /**
         * Agent readiness probe: fires on {@link ApplicationStartedEvent}
         * (before readiness is declared to the platform), not ApplicationReadyEvent.
         * <p>
         * If {@code currentComplete()} succeeds, the Agent is reachable and
         * PROPOSED_PRIV_R07 is available. If it throws
         * {@link space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicyContractViolationException},
         * the Agent has not yet implemented {@code readAcceptedComplete} — P4 is blocked.
         * If it returns empty, the Agent is not yet online — first command will retry.
         * <p>
         * Replaces the former {@code ActuatorSnapshotReaderWarmup} which used
         * {@link org.springframework.boot.context.event.ApplicationReadyEvent}
         * (too late: readiness already declared) and did not distinguish contract
         * violations from transport failures.
         */
        @Bean
        public ActuatorAgentReadinessProbe actuatorAgentReadinessProbe(
                AcceptedTracingPolicySnapshotReader snapshotReader) {
            return new ActuatorAgentReadinessProbe(snapshotReader);
        }
    }

    static class ActuatorAgentReadinessProbe {
        private static final Logger probeLog =
                LoggerFactory.getLogger(ActuatorAgentReadinessProbe.class);

        private final AcceptedTracingPolicySnapshotReader reader;

        ActuatorAgentReadinessProbe(AcceptedTracingPolicySnapshotReader reader) {
            this.reader = reader;
        }

        /**
         * Fires on {@link ApplicationStartedEvent} — after the application context
         * is fully refreshed but before the readiness state is published.
         * A contract violation here means the Agent has not implemented
         * PROPOSED_PRIV_R07 and P4 is completely blocked.
         */
        @EventListener(ApplicationStartedEvent.class)
        public void onApplicationStarted(ApplicationStartedEvent event) {
            try {
                reader.currentComplete().ifPresentOrElse(
                        s -> probeLog.info(
                                "[P4 readiness probe] Agent reachable via readAcceptedComplete: "
                                + "sequence={}", s.acceptedSequence()),
                        () -> probeLog.warn(
                                "[P4 readiness probe] Agent not yet reachable (empty result). "
                                + "First Actuator command will attempt live read.")
                );
            } catch (space.br1440.platform.tracing.autoconfigure.configuration
                     .TracingPolicyContractViolationException e) {
                probeLog.error(
                        "[P4 readiness probe] PROPOSED_PRIV_R07 (readAcceptedComplete) is not "
                        + "implemented on the Agent MBean. P4 is BLOCKED. "
                        + "Agent PR must implement AggregatePolicyControlMBean.readAcceptedComplete(). "
                        + "Cause: {}", e.getMessage());
            }
        }
    }
}
