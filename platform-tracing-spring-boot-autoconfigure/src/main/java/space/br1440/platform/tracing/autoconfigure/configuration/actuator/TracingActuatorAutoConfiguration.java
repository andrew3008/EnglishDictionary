package space.br1440.platform.tracing.autoconfigure.configuration.actuator;

import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.tracing.autoconfigure.configuration.AcceptedTracingPolicyReader;
import space.br1440.platform.tracing.autoconfigure.configuration.AcceptedTracingPolicySnapshotReader;
import space.br1440.platform.tracing.autoconfigure.configuration.PrivateJmxTracingPolicySnapshotClient;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicyContractViolationException;
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
         * Agent readiness probe implemented as {@link SmartLifecycle}.
         * <p>
         * Contract for authority=ACTUATOR:
         * <ol>
         *   <li>Private writer discovery (done at bean init)</li>
         *   <li>Protocol validation via {@code readAcceptedComplete} (PROPOSED_PRIV_R07)</li>
         *   <li>Successful complete snapshot decode</li>
         *   <li>Tracing-configuration readiness = READY</li>
         * </ol>
         * {@link SmartLifecycle#start()} is invoked during context refresh, before
         * readiness is published to the platform. A
         * {@link TracingPolicyContractViolationException} thrown from {@code start()}
         * propagates as a {@link org.springframework.context.LifecycleException} and
         * prevents startup from completing — this is the correct fail-fast behaviour.
         * <p>
         * Transport failures (Agent temporarily offline, empty Optional) are soft:
         * logged as WARN, startup continues. The first Actuator command retries live.
         * <p>
         * Phase: {@link SmartLifecycle#DEFAULT_PHASE} ({@code Integer.MAX_VALUE})
         * so the probe runs after all application beans are fully started.
         */
        @Bean
        public ActuatorAgentReadinessProbe actuatorAgentReadinessProbe(
                AcceptedTracingPolicySnapshotReader snapshotReader) {
            return new ActuatorAgentReadinessProbe(snapshotReader);
        }
    }

    static final class ActuatorAgentReadinessProbe implements SmartLifecycle {

        private static final Logger probeLog =
                LoggerFactory.getLogger(ActuatorAgentReadinessProbe.class);

        private final AcceptedTracingPolicySnapshotReader reader;
        private volatile boolean running = false;

        ActuatorAgentReadinessProbe(AcceptedTracingPolicySnapshotReader reader) {
            this.reader = reader;
        }

        /**
         * Validates PROPOSED_PRIV_R07 availability during context lifecycle.
         * <p>
         * {@link TracingPolicyContractViolationException} — thrown when the Agent
         * MBean does not expose {@code readAcceptedComplete} (ReflectionException
         * wrapping). This is a hard contract failure: P4 cannot function without
         * Agent-side PRIV-R07. The exception propagates and blocks startup.
         * <p>
         * Empty Optional — transport/online issue; soft failure, logged as WARN.
         */
        @Override
        public void start() {
            try {
                reader.currentComplete().ifPresentOrElse(
                        s -> probeLog.info(
                                "[P4 readiness probe] Agent reachable via readAcceptedComplete: "
                                + "sequence={}", s.acceptedSequence()),
                        () -> probeLog.warn(
                                "[P4 readiness probe] Agent not yet reachable (empty result). "
                                + "First Actuator command will attempt live read.")
                );
            } catch (TracingPolicyContractViolationException e) {
                // Hard failure: re-throw to block context startup.
                // SmartLifecycle.start() propagates as LifecycleException.
                throw new TracingPolicyContractViolationException(
                        "[P4 readiness probe] FATAL: readAcceptedComplete not found on Agent MBean. "
                        + "PROPOSED_PRIV_R07 is not yet implemented. "
                        + "Agent PR must expose AggregatePolicyControlMBean.readAcceptedComplete(). "
                        + "P4 startup is BLOCKED until Agent-side implementation is merged. "
                        + "Original cause: " + e.getMessage(), e);
            }
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public int getPhase() {
            return DEFAULT_PHASE; // Integer.MAX_VALUE: after all other beans
        }
    }
}
