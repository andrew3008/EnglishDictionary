package space.br1440.platform.tracing.autoconfigure;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.Bean;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.metrics.MeteredTracingRuntime;
import space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingMetrics;
import space.br1440.platform.tracing.autoconfigure.agent.AgentDeploymentExpectation;
import space.br1440.platform.tracing.autoconfigure.agent.AgentExtensionDescriptor;
import space.br1440.platform.tracing.autoconfigure.agent.AgentExtensionObserver;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeContractValidator;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.agent.OtelAgentDetector;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.otel.exception.ExceptionRecorder;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;
import space.br1440.platform.tracing.otel.runtime.otel.OtelTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.state.TracingRuntimeMode;
import space.br1440.platform.tracing.otel.semconv.policy.AttributePolicy;

@Slf4j
@AutoConfiguration
@ConditionalOnClass(OpenTelemetry.class)
@EnableConfigurationProperties(TracingProperties.class)
public class TracingRuntimeAutoConfiguration {

    private static final String DISABLED_REASON = "platform.tracing.enabled=false";

    @Bean
    public static BeanFactoryPostProcessor rejectCompetingTracingRuntimeBeans() {
        return beanFactory -> {
            rejectCompetingBeans(beanFactory, TracingRuntime.class, "tracingImplementation");
            rejectCompetingBeans(beanFactory, TraceOperations.class, "traceOperations");
            rejectCompetingBeans(beanFactory, OpenTelemetry.class, null);
        };
    }

    private static void rejectCompetingBeans(ConfigurableListableBeanFactory beanFactory,
                                             Class<?> type,
                                             String platformBeanName) {
        for (String beanName : beanFactory.getBeanNamesForType(type, false, false)) {
            if ((platformBeanName == null || !platformBeanName.equals(beanName))
                    && beanFactory instanceof BeanDefinitionRegistry registry
                    && registry.containsBeanDefinition(beanName)) {
                throw new IllegalStateException(
                        "Application-owned " + type.getSimpleName()
                                + " bean is not supported with Controlled Agent ownership: " + beanName);
            }
        }
    }

    @Bean
    public TracingDeploymentDiagnostics platformTracingDeploymentDiagnostics(
            ConfigurableListableBeanFactory beanFactory,
            TracingBootstrapSettings bootstrapSettings) {
        boolean agentPresent = OtelAgentDetector.isAgentPresent();
        boolean userBeanPresent = beanFactory.getBeanNamesForType(OpenTelemetry.class, false, false).length > 0;
        boolean globalOpenTelemetrySet = GlobalOpenTelemetry.isSet();
        AgentDeploymentExpectation expectation = bootstrapSettings.tracingEnabled()
                ? AgentDeploymentExpectation.REQUIRED
                : AgentDeploymentExpectation.FORBIDDEN;

        AgentExtensionDescriptor extension = new AgentExtensionObserver().awaitStartupDecision(
                expectation, agentPresent, userBeanPresent, globalOpenTelemetrySet);

        AgentRuntimeContractValidator.validate(bootstrapSettings.tracingEnabled(), extension);

        if (extension.state() != AgentRuntimeState.AGENT_READY
                && extension.state() != AgentRuntimeState.DISABLED) {
            log.error("Platform tracing runtime rejected: state={}, failureCode={}",
                    extension.state(), extension.failureCode());
        }

        return new TracingDeploymentDiagnostics(
                bootstrapSettings.tracingEnabled(),
                agentPresent,
                extension.state(),
                extension);
    }

    @Bean
    public TracingRuntime tracingImplementation(ObjectProvider<AttributePolicy> policyProvider,
                                                ObjectProvider<ExceptionRecorder> exceptionRecorderProvider,
                                                ObjectProvider<PlatformTracingMetrics> metricsProvider,
                                                TracingBootstrapSettings bootstrapSettings,
                                                TracingDeploymentDiagnostics deploymentDiagnostics) {
        TracingRuntime base = resolveTracingRuntime(
                policyProvider,
                exceptionRecorderProvider,
                bootstrapSettings,
                deploymentDiagnostics);
        PlatformTracingMetrics metrics = metricsProvider.getIfAvailable();
        if ((metrics != null) && (base.state().mode() == TracingRuntimeMode.ENABLED)) {
            return new MeteredTracingRuntime(base, metrics);
        }

        return base;
    }

    private TracingRuntime resolveTracingRuntime(ObjectProvider<AttributePolicy> policyProvider,
                                                 ObjectProvider<ExceptionRecorder> exceptionRecorderProvider,
                                                 TracingBootstrapSettings bootstrapSettings,
                                                 TracingDeploymentDiagnostics deploymentDiagnostics) {
        if (!bootstrapSettings.tracingEnabled()) {
            return NoOpTracingRuntime.disabledByConfiguration(DISABLED_REASON);
        }

        if (deploymentDiagnostics.runtimeState() != AgentRuntimeState.AGENT_READY) {
            throw new IllegalStateException(
                    "platform.tracing.enabled=true cannot start without READY Controlled Platform Agent: "
                            + deploymentDiagnostics.runtimeState());
        }

        if (!GlobalOpenTelemetry.isSet()) {
            throw new IllegalStateException(
                    "Controlled Platform Agent reported READY but GlobalOpenTelemetry is not registered");
        }

        AttributePolicy policy = policyProvider.getIfAvailable(AttributePolicy::new);
        ExceptionRecorder exceptionRecorder = exceptionRecorderProvider.getIfAvailable(ExceptionRecorder::secureDefault);
        return new OtelTracingRuntime(GlobalOpenTelemetry.get(), policy, exceptionRecorder);
    }

    @Bean
    public TraceOperations traceOperations(TracingRuntime tracingRuntime) {
        TracingRuntimeMode mode = tracingRuntime.state().mode();
        if (mode == TracingRuntimeMode.DISABLED_BY_CONFIGURATION) {
            return NoopTraceOperations.forDisabledRuntime((NoOpTracingRuntime) tracingRuntime);
        }

        if (mode != TracingRuntimeMode.ENABLED) {
            throw new IllegalStateException("Unexpected tracing runtime state after strict resolution: " + mode);
        }

        return new DefaultTraceOperations(tracingRuntime);
    }
}
