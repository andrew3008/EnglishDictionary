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

import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PlatformJmxControlAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsContractViolationException;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsApplier;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.CompleteTracingSettingsReader;

@AutoConfiguration
@AutoConfigureAfter(PlatformJmxControlAutoConfiguration.class)
@ConditionalOnClass(EnvironmentChangeEvent.class)
@ConditionalOnProperty(name = "platform.tracing.control.channel", havingValue = "CONFIG_SERVER")
public class ConfigServerTracingSettingsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ConfigServerSettingsProvenanceClassifier.class)
    public ConfigServerSettingsProvenanceClassifier configServerPolicyProvenanceClassifier(ConfigurableEnvironment environment) {
        return new ConfigServerSettingsProvenanceClassifier(environment);
    }

    @Bean
    @ConditionalOnMissingBean(ConfigServerSettingsManifestBuilder.class)
    public ConfigServerSettingsManifestBuilder configServerPolicyManifestBuilder(ConfigurableEnvironment environment,
                                                                               ConfigServerSettingsProvenanceClassifier provenance) {
        return new ConfigServerSettingsManifestBuilder(environment, provenance);
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
    @ConditionalOnMissingBean(TracingSettingsRefreshCoalescer.class)
    public TracingSettingsRefreshCoalescer tracingSettingsRefreshCoalescer(ConfigServerSettingsManifestBuilder manifestBuilder,
                                                                       TracingSettingsApplier snapshotClient,
                                                                       CompleteTracingSettingsReader snapshotReader,
                                                                       ExecutorService configServerPolicyRefreshExecutor) {
        return new TracingSettingsRefreshCoalescer(
                manifestBuilder,
                snapshotClient,
                snapshotReader,
                configServerPolicyRefreshExecutor
        );
    }

    @Bean
    @ConditionalOnMissingBean(ConfigServerTracingSettingsAdapter.class)
    public ConfigServerTracingSettingsAdapter configServerTracingSettingsAdapter(TracingSettingsRefreshCoalescer coalescer) {
        return new ConfigServerTracingSettingsAdapter(coalescer);
    }

    @Bean
    public SmartLifecycle configServerAgentReadinessProbe(CompleteTracingSettingsReader snapshotReader,
                                                          TracingSettingsRefreshCoalescer coalescer) {
        return new ConfigServerAgentReadinessProbe(snapshotReader, coalescer);
    }

    static final class ConfigServerAgentReadinessProbe implements SmartLifecycle {

        static final int PHASE = (SmartLifecycle.DEFAULT_PHASE - 4096);

        private final CompleteTracingSettingsReader reader;
        private final TracingSettingsRefreshCoalescer coalescer;
        private volatile boolean running;

        ConfigServerAgentReadinessProbe(CompleteTracingSettingsReader reader,
                                        TracingSettingsRefreshCoalescer coalescer) {
            this.reader = reader;
            this.coalescer = coalescer;
        }

        @Override
        public void start() {
            try {
                reader.currentComplete()
                        .orElseThrow(() -> new TracingConfigurationGuardException(
                                "[CONFIG_SERVER readiness] AcceptedPolicySnapshot is unavailable "
                                        + "or no accepted policy has been committed."));
            } catch (TracingSettingsContractViolationException e) {
                throw new TracingSettingsContractViolationException(
                        "[CONFIG_SERVER readiness] FATAL: AcceptedPolicySnapshot is unavailable or "
                                + "malformed. Original cause: " + e.getMessage(),
                        e);
            }

            TracingSettingsDeliveryResult outcome = coalescer.deliverInitial();
            if (outcome == TracingSettingsDeliveryResult.APPLIED
                    || outcome == TracingSettingsDeliveryResult.UNCHANGED) {
                running = true;
                return;
            }

            throw new TracingConfigurationGuardException(
                    "[CONFIG_SERVER readiness] initial delivery outcome=" + outcome
                            + " is not a successful start state; probe remains stopped.");
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
            return PHASE;
        }
    }
}
