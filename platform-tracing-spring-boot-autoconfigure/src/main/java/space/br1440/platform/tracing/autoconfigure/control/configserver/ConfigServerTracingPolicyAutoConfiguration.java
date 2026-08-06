package space.br1440.platform.tracing.autoconfigure.control.configserver;

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

import space.br1440.platform.tracing.autoconfigure.control.authority.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PrivateJmxControlAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.policy.TracingPolicyContractViolationException;
import space.br1440.platform.tracing.autoconfigure.control.policy.delivery.TracingPolicySnapshotApplier;
import space.br1440.platform.tracing.autoconfigure.control.policy.read.CompleteTracingPolicyReader;

@AutoConfiguration
@AutoConfigureAfter(PrivateJmxControlAutoConfiguration.class)
@ConditionalOnClass(EnvironmentChangeEvent.class)
@ConditionalOnProperty(name = "platform.tracing.configuration.authority", havingValue = "CONFIG_SERVER")
public class ConfigServerTracingPolicyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ConfigServerPolicyProvenanceClassifier.class)
    public ConfigServerPolicyProvenanceClassifier configServerPolicyProvenanceClassifier(ConfigurableEnvironment environment) {
        return new ConfigServerPolicyProvenanceClassifier(environment);
    }

    @Bean
    @ConditionalOnMissingBean(ConfigServerPolicyManifestBuilder.class)
    public ConfigServerPolicyManifestBuilder configServerPolicyManifestBuilder(ConfigurableEnvironment environment,
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
    public TracingPolicyRefreshCoalescer tracingPolicyRefreshCoalescer(ConfigServerPolicyManifestBuilder manifestBuilder,
                                                                       TracingPolicySnapshotApplier snapshotClient,
                                                                       CompleteTracingPolicyReader snapshotReader,
                                                                       ExecutorService configServerPolicyRefreshExecutor) {
        return new TracingPolicyRefreshCoalescer(
                manifestBuilder,
                snapshotClient,
                snapshotReader,
                configServerPolicyRefreshExecutor
        );
    }

    @Bean
    @ConditionalOnMissingBean(ConfigServerTracingPolicyAdapter.class)
    public ConfigServerTracingPolicyAdapter configServerTracingPolicyAdapter(TracingPolicyRefreshCoalescer coalescer) {
        return new ConfigServerTracingPolicyAdapter(coalescer);
    }

    @Bean
    public SmartLifecycle configServerAgentReadinessProbe(CompleteTracingPolicyReader snapshotReader,
                                                          TracingPolicyRefreshCoalescer coalescer) {
        return new ConfigServerAgentReadinessProbe(snapshotReader, coalescer);
    }

    static final class ConfigServerAgentReadinessProbe implements SmartLifecycle {

        private final CompleteTracingPolicyReader reader;
        private final TracingPolicyRefreshCoalescer coalescer;
        private volatile boolean running;

        ConfigServerAgentReadinessProbe(CompleteTracingPolicyReader reader,
                                        TracingPolicyRefreshCoalescer coalescer) {
            this.reader = reader;
            this.coalescer = coalescer;
        }

        @Override
        public void start() {
            try {
                reader.currentComplete()
                        .orElseThrow(() -> new TracingConfigurationGuardException(
                                "[CONFIG_SERVER readiness] private writer unreachable or has not "
                                        + "committed an accepted policy (readAcceptedComplete empty)."));
            } catch (TracingPolicyContractViolationException e) {
                throw new TracingPolicyContractViolationException(
                        "[CONFIG_SERVER readiness] FATAL: readAcceptedComplete unavailable or "
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
    }
}
