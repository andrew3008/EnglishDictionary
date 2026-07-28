package space.br1440.platform.tracing.autoconfigure.configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
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
        return new TracingConfigurationAuthorityGuard().validate(
                properties.getMode(),
                properties.getConfiguration().getAuthority(),
                environment.getProperty(PLATFORM_ENVIRONMENT),
                properties.getActuator().isControlEnabled(),
                properties.getActuator().isMutationEnabled());
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
    @ConditionalOnProperty(
            prefix = "platform.tracing.configuration",
            name = "authority",
            havingValue = "CONFIG_SERVER")
    TracingConfigurationReadiness tracingConfigurationReadiness() {
        return new TracingConfigurationReadiness();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "platform.tracing.configuration",
            name = "authority",
            havingValue = "CONFIG_SERVER")
    TracingPolicyDeliveryDiagnostics tracingPolicyDeliveryDiagnostics() {
        return new TracingPolicyDeliveryDiagnostics();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "platform.tracing.configuration",
            name = "authority",
            havingValue = "CONFIG_SERVER")
    PrivateControlTracingPolicyClient configServerTracingPolicyClient(
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
            PrivateControlTracingPolicyClient client,
            TracingConfigurationReadiness readiness,
            TracingPolicyDeliveryDiagnostics diagnostics,
            ExecutorService tracingPolicyRefreshExecutor) {
        return new ConfigServerTracingPolicyAdapter(
                environment,
                manifestBuilder,
                client,
                client,
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
        return adapter::applyInitial;
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
    @ConditionalOnProperty(
            prefix = "platform.tracing.configuration",
            name = "authority",
            havingValue = "ACTUATOR")
    PrivateControlTracingPolicyClient actuatorTracingPolicyClient(
            TracingConfigurationAuthorityGuard.AuthorityPostureDecision decision) {
        if (!decision.actuatorWriteSurfaceAllowed()) {
            throw new IllegalStateException("Actuator write posture was not approved");
        }
        PrivateControlTracingPolicyClient client = new PrivateControlTracingPolicyClient();
        client.verifyReady();
        return client;
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "platform.tracing.configuration",
            name = "authority",
            havingValue = "ACTUATOR")
    TracingPolicyDeliveryDiagnostics actuatorTracingPolicyDeliveryDiagnostics() {
        return new TracingPolicyDeliveryDiagnostics();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "platform.tracing.configuration",
            name = "authority",
            havingValue = "ACTUATOR")
    TracingConfigurationReadiness actuatorTracingConfigurationReadiness() {
        TracingConfigurationReadiness readiness = new TracingConfigurationReadiness();
        readiness.recordInitial(new TracingPolicyDeliveryResult(
                TracingPolicyDeliveryOutcome.UNCHANGED,
                "",
                "CONTROL_READY",
                0L));
        return readiness;
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "platform.tracing.configuration",
            name = "authority",
            havingValue = "ACTUATOR")
    SpringActuatorTracingPolicyAdapter springActuatorTracingPolicyAdapter(
            PrivateControlTracingPolicyClient client,
            TracingPolicyDeliveryDiagnostics diagnostics) {
        return new SpringActuatorTracingPolicyAdapter(client, client, diagnostics);
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.web.annotation.RestControllerEndpoint")
    @ConditionalOnProperty(
            prefix = "platform.tracing.configuration",
            name = "authority",
            havingValue = "ACTUATOR")
    TracingActuatorControlEndpoint tracingActuatorControlEndpoint(
            SpringActuatorTracingPolicyAdapter adapter) {
        return new TracingActuatorControlEndpoint(adapter);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "platform.tracing.configuration",
            name = "authority",
            havingValue = "CONFIG_SERVER")
    TracingConfigurationReadModel configServerTracingConfigurationReadModel(
            PrivateControlTracingPolicyClient client,
            TracingConfigurationReadiness readiness,
            TracingPolicyDeliveryDiagnostics diagnostics) {
        return new TracingConfigurationReadModel(
                TracingConfigurationAuthority.CONFIG_SERVER,
                client,
                readiness,
                diagnostics);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "platform.tracing.configuration",
            name = "authority",
            havingValue = "ACTUATOR")
    TracingConfigurationReadModel actuatorTracingConfigurationReadModel(
            PrivateControlTracingPolicyClient client,
            TracingConfigurationReadiness readiness,
            TracingPolicyDeliveryDiagnostics diagnostics) {
        return new TracingConfigurationReadModel(
                TracingConfigurationAuthority.ACTUATOR,
                client,
                readiness,
                diagnostics);
    }
}
