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
import space.br1440.platform.tracing.autoconfigure.configuration.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicyContractViolationException;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicySnapshotClient;
import space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlLocator;
import space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlOpenTypeCodec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Auto-configuration for P4 unified tracing policy adapters.
 * <p>
 * PRIV-R07 (APPROVED 2026-07-29): PrivateJmxTracingPolicySnapshotClient calls
 * {@code readAcceptedComplete} (one bounded internal read; Agent-side implemented in
 * commit 00b987a). No InProcessAcceptedSnapshotStore — Agent is the sole accepted-state
 * owner. See docs/research/evidence/priv-r07-proposal/decision-summary.json.
 * If the Agent MBean ever stops exposing {@code readAcceptedComplete} (e.g. rollback),
 * every call to AcceptedTracingPolicySnapshotReader.currentComplete() throws
 * TracingPolicyContractViolationException (ReflectionException wrapping) and startup
 * fails fast for ACTUATOR authority — see {@link ActuatorAgentReadinessProbe}.
 * <p>
 * TracingControlMvcController registered via TracingControlManagementContextConfiguration
 * (discovered through ManagementContextConfiguration.imports).
 * Registration requires BOTH: authority=ACTUATOR AND platform.environment=LOCAL.
 */
@AutoConfiguration
public class TracingActuatorAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TracingActuatorAutoConfiguration.class);

    /**
     * Discovers the Agent-side private control plane. Gated on
     * {@code authority=ACTUATOR}. {@code CONFIG_SERVER} wires the same private
     * apply/read contract via {@code ConfigServerTracingPolicyAutoConfiguration}.
     * {@code HELM} never discovers the private control plane from Spring.
     * <p>
     * Previously this bean was never produced in any production autoconfiguration
     * path — {@link PrivateJmxTracingPolicySnapshotClient}'s
     * {@code @ConditionalOnBean({PrivateControlLocator.class, PrivateControlOpenTypeCodec.class})}
     * could never be satisfied, so a real Spring context with {@code authority=ACTUATOR}
     * could never successfully wire the private JMX client, the readiness probe's
     * required {@link AcceptedTracingPolicySnapshotReader} dependency, or
     * {@link SpringActuatorTracingPolicyAdapter} — a confirmed defect blocking any
     * real ACTUATOR-authority startup.
     */
    @Bean
    @ConditionalOnMissingBean(PrivateControlLocator.class)
    @ConditionalOnProperty(
            name = "platform.tracing.configuration.authority",
            havingValue = "ACTUATOR",
            matchIfMissing = false)
    public PrivateControlLocator privateControlLocator() {
        return new PrivateControlLocator();
    }

    @Bean
    @ConditionalOnMissingBean(PrivateControlOpenTypeCodec.class)
    @ConditionalOnProperty(
            name = "platform.tracing.configuration.authority",
            havingValue = "ACTUATOR",
            matchIfMissing = false)
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
    @ConditionalOnBean(AcceptedTracingPolicyReader.class)
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
         * Required startup sequence for authority=ACTUATOR:
         * <ol>
         *   <li>Private writer discovery (done at bean init via initialize())</li>
         *   <li>Protocol validation: {@code readAcceptedComplete} must succeed
         *       (PRIV-R07 — APPROVED 2026-07-29)</li>
         *   <li>Complete snapshot decoded successfully</li>
         *   <li>Tracing-configuration readiness = READY</li>
         * </ol>
         * Both failure modes from {@link #start()} are hard failures that
         * propagate as {@link org.springframework.context.LifecycleException}
         * and prevent the context from completing startup:
         * <ul>
         *   <li>{@link TracingPolicyContractViolationException}: Agent MBean does
         *       not expose {@code readAcceptedComplete} — PRIV-R07 unavailable.
         *       Re-thrown as-is.</li>
         *   <li>{@link TracingConfigurationGuardException}: Agent is reachable but
         *       returned an empty snapshot — transport or availability issue.
         *       ACTUATOR authority cannot begin read-copy-change without an initial
         *       complete accepted policy and known sequence.</li>
         * </ul>
         * Phase: {@link SmartLifecycle#DEFAULT_PHASE} ({@code Integer.MAX_VALUE}),
         * meaning this probe starts after all other {@link SmartLifecycle} components
         * with lower phase values. It does not guarantee that all external
         * initialisation processes have completed.
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
         * Validates PRIV-R07 availability and obtains an initial complete
         * accepted snapshot during context lifecycle.
         * <p>
         * Hard failures (both block startup via propagation from start()):
         * <ul>
         *   <li>{@link TracingPolicyContractViolationException}: Agent MBean does not
         *       expose {@code readAcceptedComplete}. Re-thrown directly.</li>
         *   <li>{@link TracingConfigurationGuardException}: {@code currentComplete()}
         *       returned empty. For ACTUATOR authority an empty snapshot at startup
         *       is not a transient issue — it means the control plane cannot safely
         *       execute read-copy-change-apply (expected sequence unknown, all eight
         *       policy leaves unknown). Startup must not complete.</li>
         * </ul>
         */
        @Override
        public void start() {
            try {
                var accepted = reader.currentComplete()
                        .orElseThrow(() -> new TracingConfigurationGuardException(
                                "[P4 readiness probe] ACTUATOR authority requires a complete accepted "
                                + "snapshot at startup. currentComplete() returned empty — Agent "
                                + "is unreachable or has not yet committed an accepted policy. "
                                + "Application tracing-configuration readiness is NOT achieved."));

                probeLog.info(
                        "[P4 readiness probe] Agent reachable via readAcceptedComplete: "
                        + "sequence={}", accepted.acceptedSequence());

            } catch (TracingPolicyContractViolationException e) {
                throw new TracingPolicyContractViolationException(
                        "[P4 readiness probe] FATAL: readAcceptedComplete not found on Agent MBean, "
                        + "or returned a malformed/incompatible response. PRIV-R07 requires a "
                        + "compatible Agent build. Startup is BLOCKED until a compatible Agent "
                        + "is attached. Original cause: " + e.getMessage(), e);
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
            // DEFAULT_PHASE = Integer.MAX_VALUE: starts after SmartLifecycle
            // components with lower phase values. Does not guarantee completion
            // of external initialisation processes outside the lifecycle.
            return DEFAULT_PHASE;
        }
    }
}
