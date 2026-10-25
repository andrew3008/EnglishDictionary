package space.br1440.platform.tracing.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.agent.AgentStartupVerifier;
import space.br1440.platform.tracing.autoconfigure.agent.OtelJavaAgentMarkerDetector;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.control.channel.TracingControlChannelAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.metrics.MeteredTracingRuntime;
import space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingMetrics;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidator;
import space.br1440.platform.tracing.otel.exception.ExceptionRecorder;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.otel.OtelTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.state.TracingRuntimeMode;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;

@AutoConfiguration
@AutoConfigureAfter(TracingControlChannelAutoConfiguration.class)
@ConditionalOnClass(OpenTelemetry.class)
@EnableConfigurationProperties(TracingProperties.class)
@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
public class TracingRuntimeAutoConfiguration {

    private static final String DISABLED_REASON = "platform.tracing.enabled=false";
    private static final String DUAL_SDK_REJECTION = """
            Platform tracing startup rejected: runtimeState=%s, reason=DUAL_SDK_COLLISION. \
            Root cause: the application declares an OpenTelemetry bean, but the Controlled Platform Agent \
            already owns the OpenTelemetry runtime\
            """.formatted(AgentRuntimeState.DUAL_SDK_DETECTED);

    @Bean
    public static BeanFactoryPostProcessor rejectCompetingTracingRuntimeBeans() {
        return beanFactory -> {
            rejectCompetingBeans(beanFactory, TracingRuntime.class, "tracingImplementation");
            rejectCompetingBeans(beanFactory, TraceOperations.class, "traceOperations");
            rejectCompetingBeans(beanFactory, OpenTelemetry.class, null);
            rejectCompetingBeans(
                    beanFactory,
                    AgentReadinessCoherenceGuard.class,
                    "agentReadinessCoherenceGuard"
            );
            rejectCompetingBeans(beanFactory, AgentStartupVerifier.class, "agentStartupVerifier");
        };
    }

    private static void rejectCompetingBeans(ConfigurableListableBeanFactory beanFactory,
                                             Class<?> type,
                                             String platformBeanName) {
        for (String singletonName : beanFactory.getSingletonNames()) {
            if (type.isInstance(beanFactory.getSingleton(singletonName))) {
                rejectCompetingBean(type, singletonName);
            }
        }

        for (String beanName : beanFactory.getBeanNamesForType(type, false, false)) {
            if (!isPlatformOwnedDefinition(beanFactory, beanName, platformBeanName)) {
                rejectCompetingBean(type, beanName);
            }
        }
    }

    private static void rejectCompetingBean(Class<?> type, String beanName) {
        if (OpenTelemetry.class.equals(type)) {
            String detail = "Conflicting bean: type=%s, name=%s".formatted(type.getName(), beanName);
            String message = DUAL_SDK_REJECTION + ". " + detail
                    + ". Remove the application-owned OpenTelemetry bean or its SDK auto-configuration";

            throw new IllegalStateException(message);
        }

        String message = "Application-owned %s bean is not supported with Controlled Agent ownership: %s"
                .formatted(type.getSimpleName(), beanName);
        throw new IllegalStateException(message);
    }

    private static boolean isPlatformOwnedDefinition(ConfigurableListableBeanFactory beanFactory,
                                                      String beanName,
                                                      String platformBeanName) {
        if (platformBeanName == null
                || !platformBeanName.equals(beanName)
                || beanFactory.containsSingleton(beanName)
                || !beanFactory.containsBeanDefinition(beanName)) {
            return false;
        }

        BeanDefinition definition = beanFactory.getBeanDefinition(beanName);
        String factoryBeanName = definition.getFactoryBeanName();
        return platformBeanName.equals(definition.getFactoryMethodName())
                && factoryBeanName != null
                && TracingRuntimeAutoConfiguration.class.equals(beanFactory.getType(factoryBeanName, false));
    }

    @Bean
    public AgentReadinessCoherenceGuard agentReadinessCoherenceGuard() {
        return new AgentReadinessCoherenceGuard();
    }

    @Bean
    public AgentStartupVerifier agentStartupVerifier(AgentReadinessCoherenceGuard coherenceGuard) {
        return new AgentStartupVerifier(coherenceGuard);
    }

    @Bean
    @Lazy(false)
    public TracingDeploymentDiagnostics platformTracingDeploymentDiagnostics(ConfigurableListableBeanFactory beanFactory,
                                                                             TracingBootstrapSettings bootstrapSettings,
                                                                             AgentStartupVerifier agentStartupVerifier) {
        boolean agentPresent = OtelJavaAgentMarkerDetector.isAgentPresent();
        boolean userBeanPresent = beanFactory.getBeanNamesForType(OpenTelemetry.class, false, false).length > 0;
        boolean globalOpenTelemetrySet = GlobalOpenTelemetry.isSet();

        return agentStartupVerifier.verify(
                bootstrapSettings.tracingEnabled(),
                agentPresent,
                userBeanPresent,
                globalOpenTelemetrySet
        );
    }

    @Bean
    public TracingRuntime tracingImplementation(ObjectProvider<SpanAttributeValidator> policyProvider,
                                                ObjectProvider<ExceptionRecorder> exceptionRecorderProvider,
                                                ObjectProvider<PlatformTracingMetrics> metricsProvider,
                                                TracingBootstrapSettings bootstrapSettings,
                                                TracingDeploymentDiagnostics deploymentDiagnostics) {
        TracingRuntime base = resolveTracingRuntime(
                policyProvider,
                exceptionRecorderProvider,
                bootstrapSettings,
                deploymentDiagnostics
        );

        PlatformTracingMetrics metrics = metricsProvider.getIfAvailable();
        if ((metrics != null) && (base.state().mode() == TracingRuntimeMode.ENABLED)) {
            return new MeteredTracingRuntime(base, metrics);
        }

        return base;
    }

    private TracingRuntime resolveTracingRuntime(ObjectProvider<SpanAttributeValidator> policyProvider,
                                                 ObjectProvider<ExceptionRecorder> exceptionRecorderProvider,
                                                 TracingBootstrapSettings bootstrapSettings,
                                                 TracingDeploymentDiagnostics deploymentDiagnostics) {
        if (!bootstrapSettings.tracingEnabled()) {
            return NoOpTracingRuntime.disabledByConfiguration(DISABLED_REASON);
        }

        if (deploymentDiagnostics.runtimeState() != AgentRuntimeState.AGENT_READY) {
            throw new IllegalStateException(
                    "platform.tracing.enabled=true cannot start without READY Controlled Platform Agent: "
                            + deploymentDiagnostics.runtimeState()
            );
        }

        if (!GlobalOpenTelemetry.isSet()) {
            throw new IllegalStateException("Controlled Platform Agent reported READY but GlobalOpenTelemetry is not registered");
        }

        SpanAttributeValidator policy = policyProvider.getIfAvailable(SpanAttributeValidator::new);
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
