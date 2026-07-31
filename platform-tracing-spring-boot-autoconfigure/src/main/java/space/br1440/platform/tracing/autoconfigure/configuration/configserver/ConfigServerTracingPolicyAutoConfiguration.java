package space.br1440.platform.tracing.autoconfigure.configuration.configserver;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;

import space.br1440.platform.tracing.autoconfigure.configuration.AcceptedTracingPolicySnapshotReader;
import space.br1440.platform.tracing.autoconfigure.configuration.PrivateJmxControlAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicyContractViolationException;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicySnapshotClient;

/**
 * P6.2: CONFIG_SERVER authority wiring onto the private complete-snapshot writer.
 *
 * <p>Registers complete-manifest builder, coalescer and EnvironmentChangeEvent adapter.
 * Shared private JMX beans are registered by
 * {@link PrivateJmxControlAutoConfiguration}.
 * Does <strong>not</strong> register any Actuator write surface. HELM and DISABLED never
 * load this configuration.
 */
@AutoConfiguration
@AutoConfigureAfter(PrivateJmxControlAutoConfiguration.class)
@ConditionalOnClass(EnvironmentChangeEvent.class)
@ConditionalOnProperty(
        name = "platform.tracing.configuration.enabled",
        havingValue = "true",
        matchIfMissing = true)
@ConditionalOnProperty(
        name = "platform.tracing.configuration.authority",
        havingValue = "CONFIG_SERVER",
        matchIfMissing = false)
public class ConfigServerTracingPolicyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ConfigServerPolicyProvenanceClassifier.class)
    public ConfigServerPolicyProvenanceClassifier configServerPolicyProvenanceClassifier(
            ConfigurableEnvironment environment) {
        return new ConfigServerPolicyProvenanceClassifier(environment);
    }

    @Bean
    @ConditionalOnMissingBean(ConfigServerPolicyManifestBuilder.class)
    public ConfigServerPolicyManifestBuilder configServerPolicyManifestBuilder(
            ConfigurableEnvironment environment,
            ConfigServerPolicyProvenanceClassifier provenance) {
        return new ConfigServerPolicyManifestBuilder(environment, provenance);
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "configServerPolicyRefreshExecutor")
    public ExecutorService configServerPolicyRefreshExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "platform-tracing-config-server-refresh");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    @ConditionalOnMissingBean(TracingPolicyRefreshCoalescer.class)
    public TracingPolicyRefreshCoalescer tracingPolicyRefreshCoalescer(
            ConfigServerPolicyManifestBuilder manifestBuilder,
            TracingPolicySnapshotClient snapshotClient,
            AcceptedTracingPolicySnapshotReader snapshotReader,
            ExecutorService configServerPolicyRefreshExecutor) {
        Executor worker = configServerPolicyRefreshExecutor;
        return new TracingPolicyRefreshCoalescer(
                manifestBuilder,
                snapshotClient,
                snapshotReader,
                worker);
    }

    @Bean
    @ConditionalOnMissingBean(ConfigServerTracingPolicyAdapter.class)
    public ConfigServerTracingPolicyAdapter configServerTracingPolicyAdapter(
            TracingPolicyRefreshCoalescer coalescer) {
        return new ConfigServerTracingPolicyAdapter(coalescer);
    }

    /**
     * Fail-fast readiness: private writer must be discoverable and
     * {@code readAcceptedComplete} must succeed before CONFIG_SERVER delivery is READY.
     */
    @Bean
    public ConfigServerAgentReadinessProbe configServerAgentReadinessProbe(
            AcceptedTracingPolicySnapshotReader snapshotReader,
            TracingPolicyRefreshCoalescer coalescer) {
        return new ConfigServerAgentReadinessProbe(snapshotReader, coalescer);
    }

    static final class ConfigServerAgentReadinessProbe implements SmartLifecycle {

        private final AcceptedTracingPolicySnapshotReader reader;
        private final TracingPolicyRefreshCoalescer coalescer;
        private volatile boolean running;

        ConfigServerAgentReadinessProbe(
                AcceptedTracingPolicySnapshotReader reader,
                TracingPolicyRefreshCoalescer coalescer) {
            this.reader = reader;
            this.coalescer = coalescer;
        }

        @Override
        public void start() {
            try {
                reader.currentComplete()
                        .orElseThrow(() -> new TracingConfigurationGuardException(
                                "[P6 CONFIG_SERVER readiness] private writer unreachable or has not "
                                        + "committed an accepted policy (readAcceptedComplete empty)."));
            } catch (TracingPolicyContractViolationException e) {
                throw new TracingPolicyContractViolationException(
                        "[P6 CONFIG_SERVER readiness] FATAL: readAcceptedComplete unavailable or "
                                + "malformed. Original cause: " + e.getMessage(),
                        e);
            }
            coalescer.deliverInitial();
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
            return DEFAULT_PHASE;
        }
    }
}
