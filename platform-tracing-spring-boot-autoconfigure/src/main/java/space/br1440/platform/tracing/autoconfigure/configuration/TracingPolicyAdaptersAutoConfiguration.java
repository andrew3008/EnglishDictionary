package space.br1440.platform.tracing.autoconfigure.configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import space.br1440.platform.tracing.autoconfigure.TracingProperties;

/**
 * P4 composition root. До P6 конфигурация намеренно отсутствует в AutoConfiguration.imports.
 */
@AutoConfiguration
@EnableConfigurationProperties(TracingProperties.class)
public class TracingPolicyAdaptersAutoConfiguration {

    private static final String PLATFORM_ENVIRONMENT = "platform.environment";

    @Bean
    TracingConfigurationAuthorityGuard.AuthorityPostureDecision
            tracingConfigurationAuthorityPosture(
                    TracingProperties properties,
                    Environment environment) {
        return TracingConfigurationAuthorityGuard.validate(
                properties.getMode(),
                properties.getConfiguration().getAuthority(),
                environment.getProperty(PLATFORM_ENVIRONMENT),
                properties.getActuator().isMutationEnabled());
    }

    @Bean
    @ConditionalOnMissingBean(AcceptedTracingPolicyReader.class)
    @ConditionalOnProperty(
            prefix = "platform.tracing",
            name = "mode",
            havingValue = "AGENT",
            matchIfMissing = true)
    AcceptedTracingPolicyReader acceptedTracingPolicyReader() {
        return new PrivateControlAcceptedTracingPolicyReader();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "platform.tracing",
            name = "mode",
            havingValue = "AGENT",
            matchIfMissing = true)
    TracingConfigurationReadiness tracingConfigurationReadiness(
            TracingConfigurationAuthorityGuard.AuthorityPostureDecision decision,
            AcceptedTracingPolicyReader acceptedReader) {
        TracingConfigurationReadiness readiness = new TracingConfigurationReadiness();
        if (decision.authority() != TracingConfigurationAuthority.CONFIG_SERVER) {
            readiness.recordAcceptedStateAvailable(acceptedReader.current());
        }
        return readiness;
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "platform.tracing",
            name = "mode",
            havingValue = "AGENT",
            matchIfMissing = true)
    TracingPolicyDeliveryDiagnostics tracingPolicyDeliveryDiagnostics() {
        return new TracingPolicyDeliveryDiagnostics();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "platform.tracing.configuration",
            name = "authority",
            havingValue = "CONFIG_SERVER")
    ConfigServerPolicyManifestBuilder configServerPolicyManifestBuilder() {
        if (!ClassUtils.isPresent(
                "org.springframework.cloud.context.environment.EnvironmentChangeEvent",
                getClass().getClassLoader())) {
            throw new IllegalStateException(
                    "CONFIG_SERVER authority requires Spring Cloud Context");
        }
        return new ConfigServerPolicyManifestBuilder(new ConfigServerProvenanceClassifier());
    }

    @Bean
    @ConditionalOnMissingBean(TracingPolicySnapshotClient.class)
    @ConditionalOnProperty(
            prefix = "platform.tracing.configuration",
            name = "authority",
            havingValue = "CONFIG_SERVER")
    TracingPolicySnapshotClient configServerTracingPolicyClient(
            TracingConfigurationAuthorityGuard.AuthorityPostureDecision decision) {
        if (!decision.configServerDeliveryRequired()) {
            throw new IllegalStateException("Config Server delivery posture was not approved");
        }
        return new PrivateControlTracingPolicyClient();
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnClass(name = "org.springframework.cloud.context.environment.EnvironmentChangeEvent")
    @ConditionalOnProperty(
            prefix = "platform.tracing.configuration",
            name = "authority",
            havingValue = "CONFIG_SERVER")
    ExecutorService tracingPolicyRefreshExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "tracing-policy-refresh");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.cloud.context.environment.EnvironmentChangeEvent")
    @ConditionalOnProperty(
            prefix = "platform.tracing.configuration",
            name = "authority",
            havingValue = "CONFIG_SERVER")
    ConfigServerTracingPolicyAdapter configServerTracingPolicyAdapter(
            ConfigurableEnvironment environment,
            ConfigServerPolicyManifestBuilder manifestBuilder,
            AcceptedTracingPolicyReader acceptedReader,
            TracingPolicySnapshotClient snapshotClient,
            TracingConfigurationReadiness readiness,
            TracingPolicyDeliveryDiagnostics diagnostics,
            ExecutorService tracingPolicyRefreshExecutor) {
        return new ConfigServerTracingPolicyAdapter(
                environment,
                manifestBuilder,
                acceptedReader,
                snapshotClient,
                readiness,
                diagnostics,
                tracingPolicyRefreshExecutor);
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.cloud.context.environment.EnvironmentChangeEvent")
    @ConditionalOnProperty(
            prefix = "platform.tracing.configuration",
            name = "authority",
            havingValue = "CONFIG_SERVER")
    SmartInitializingSingleton tracingPolicyInitialDelivery(
            ConfigServerTracingPolicyAdapter adapter) {
        return () -> requireSuccessfulInitialDelivery(adapter.applyInitial());
    }

    @Bean(name = "tracingConfigurationHealthIndicator")
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnProperty(
            prefix = "platform.tracing.configuration",
            name = "authority",
            havingValue = "CONFIG_SERVER")
    HealthIndicator tracingConfigurationHealthIndicator(
            TracingConfigurationReadiness readiness) {
        return new ApplicationTracingConfigurationHealthIndicator(readiness);
    }

    @Bean
    @ConditionalOnMissingBean(TracingPolicySnapshotClient.class)
    @ConditionalOnProperty(
            prefix = "platform.tracing.configuration",
            name = "authority",
            havingValue = "ACTUATOR")
    TracingPolicySnapshotClient actuatorTracingPolicyClient(
            TracingConfigurationAuthorityGuard.AuthorityPostureDecision decision) {
        if (!decision.actuatorWriteSurfaceAllowed()) {
            throw new IllegalStateException("Actuator write posture was not approved");
        }
        return new PrivateControlTracingPolicyClient();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "platform.tracing.configuration",
            name = "authority",
            havingValue = "ACTUATOR")
    SpringActuatorTracingPolicyAdapter springActuatorTracingPolicyAdapter(
            AcceptedTracingPolicyReader acceptedReader,
            TracingPolicySnapshotClient snapshotClient,
            TracingPolicyDeliveryDiagnostics diagnostics) {
        return new SpringActuatorTracingPolicyAdapter(
                acceptedReader,
                snapshotClient,
                diagnostics);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "platform.tracing",
            name = "mode",
            havingValue = "AGENT",
            matchIfMissing = true)
    TracingConfigurationReadModel tracingConfigurationReadModel(
            TracingConfigurationAuthorityGuard.AuthorityPostureDecision decision,
            AcceptedTracingPolicyReader acceptedReader,
            TracingConfigurationReadiness readiness,
            TracingPolicyDeliveryDiagnostics diagnostics) {
        return new TracingConfigurationReadModel(
                decision.authority(),
                acceptedReader,
                readiness,
                diagnostics);
    }

    static void requireSuccessfulInitialDelivery(
            TracingPolicyDeliveryResult result) {
        if (result.outcome() != TracingPolicyDeliveryOutcome.APPLIED
                && result.outcome() != TracingPolicyDeliveryOutcome.UNCHANGED) {
            throw new IllegalStateException(
                    "Initial Config Server tracing policy delivery failed: "
                            + result.messageCode());
        }
    }
}
