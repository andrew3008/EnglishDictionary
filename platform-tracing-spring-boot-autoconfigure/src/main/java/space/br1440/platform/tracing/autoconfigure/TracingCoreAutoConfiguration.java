package space.br1440.platform.tracing.autoconfigure;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.Bean;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.propagation.PlatformContextPropagation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.SpanFactoryDiagnostics;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.metrics.MeteredTracingRuntime;
import space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingMetrics;
import space.br1440.platform.tracing.autoconfigure.support.AgentExtensionDescriptor;
import space.br1440.platform.tracing.autoconfigure.support.AgentExtensionObserver;
import space.br1440.platform.tracing.autoconfigure.support.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.support.OtelAgentDetector;
import space.br1440.platform.tracing.autoconfigure.support.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.autoconfigure.support.SdkMode;
import space.br1440.platform.tracing.autoconfigure.support.SdkModeDiagnostics;
import space.br1440.platform.tracing.autoconfigure.support.SdkModeResolver;
import space.br1440.platform.tracing.core.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.core.propagation.NoOpPlatformContextPropagation;
import space.br1440.platform.tracing.core.facade.NoopTraceOperations;
import space.br1440.platform.tracing.core.propagation.OtelPlatformContextPropagation;
import space.br1440.platform.tracing.core.runtime.otel.OtelTracingRuntime;
import space.br1440.platform.tracing.core.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.core.runtime.TracingRuntime;
import space.br1440.platform.tracing.core.runtime.state.TracingMode;

/**
 * Базовая авто-конфигурация платформенного модуля трассировки.
 * <p>
 * Slice 2: registers {@link TracingRuntime} as the internal span-creation boundary and
 * {@link TraceOperations} as a thin facade over it.
 * <p>
 * Platform runtime и facade принадлежат этому composition root. Пользовательский runtime не
 * является production extension point: конкурирующие beans должны приводить к явной ошибке
 * Spring wiring, а не к скрытому второму SDK.
 */
@AutoConfiguration
@ConditionalOnClass(OpenTelemetry.class)
@EnableConfigurationProperties(TracingProperties.class)
public class TracingCoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TracingCoreAutoConfiguration.class);

    @Bean
    public static BeanFactoryPostProcessor rejectCompetingTracingRuntimeBeans() {
        return beanFactory -> {
            rejectCompetingBeans(beanFactory, TracingRuntime.class, "tracingImplementation");
            rejectCompetingBeans(beanFactory, TraceOperations.class, "traceOperations");
            rejectCompetingBeans(beanFactory, OpenTelemetry.class, null);
        };
    }

    private static void rejectCompetingBeans(
            ConfigurableListableBeanFactory beanFactory,
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
    public SdkModeDiagnostics platformSdkModeDiagnostics(
            ConfigurableListableBeanFactory beanFactory,
            TracingProperties properties) {
        boolean agentPresent = OtelAgentDetector.isAgentPresent();
        boolean userBeanPresent = beanFactory.getBeanNamesForType(OpenTelemetry.class, false, false).length > 0;
        boolean globalOpenTelemetrySet = GlobalOpenTelemetry.isSet();
        boolean configuredDisabled = properties.getSdk().getMode() == SdkMode.DISABLED;
        AgentExtensionDescriptor extension = new AgentExtensionObserver().awaitStartupDecision(
                configuredDisabled, agentPresent, userBeanPresent, globalOpenTelemetrySet);
        SdkMode resolved = SdkModeResolver.resolve(
                properties.getSdk().getMode(),
                properties.isEnabled(),
                extension);

        if (extension.state() != AgentRuntimeState.AGENT_READY
                && extension.state() != AgentRuntimeState.DISABLED) {
            log.error("Platform tracing runtime rejected: state={}, failureCode={}",
                    extension.state(), extension.failureCode());
        }
        log.info("Платформенная трассировка: SDK mode={} (enabled={}, runtimeState={}, agentMarker={})",
                resolved, properties.isEnabled(), extension.state(), agentPresent);
        return new SdkModeDiagnostics(
                resolved,
                properties.isEnabled(),
                agentPresent,
                extension.state(),
                extension);
    }

    @Bean
    public TracingRuntime tracingImplementation(
            org.springframework.beans.factory.ObjectProvider<
                    space.br1440.platform.tracing.core.semconv.policy.AttributePolicy> policyProvider,
            org.springframework.beans.factory.ObjectProvider<
                    space.br1440.platform.tracing.core.exception.ExceptionRecorder> exceptionRecorderProvider,
            org.springframework.beans.factory.ObjectProvider<PlatformTracingMetrics> metricsProvider,
            SdkModeDiagnostics sdkModeDiagnostics) {
        TracingRuntime base = resolveTracingRuntime(
                policyProvider,
                exceptionRecorderProvider,
                sdkModeDiagnostics);
        PlatformTracingMetrics metrics = metricsProvider.getIfAvailable();
        if (metrics != null && base.state().mode() == TracingMode.ENABLED) {
            return new MeteredTracingRuntime(base, metrics);
        }
        return base;
    }

    private TracingRuntime resolveTracingRuntime(
            org.springframework.beans.factory.ObjectProvider<
                    space.br1440.platform.tracing.core.semconv.policy.AttributePolicy> policyProvider,
            org.springframework.beans.factory.ObjectProvider<
                    space.br1440.platform.tracing.core.exception.ExceptionRecorder> exceptionRecorderProvider,
            SdkModeDiagnostics sdkModeDiagnostics) {
        if (sdkModeDiagnostics.mode() == SdkMode.DISABLED) {
            log.info("platform.tracing.sdk.mode=DISABLED — TracingRuntime DISABLED_BY_CONFIGURATION");
            return NoOpTracingRuntime.disabledByConfiguration("platform.tracing.sdk.mode=DISABLED");
        }
        if (sdkModeDiagnostics.runtimeState() != AgentRuntimeState.AGENT_READY) {
            throw new IllegalStateException(
                    "AGENT mode cannot start without READY Controlled Platform Agent: "
                            + sdkModeDiagnostics.runtimeState());
        }

        space.br1440.platform.tracing.core.semconv.policy.AttributePolicy policy =
                policyProvider.getIfAvailable(space.br1440.platform.tracing.core.semconv.policy.AttributePolicy::new);
        space.br1440.platform.tracing.core.exception.ExceptionRecorder exceptionRecorder =
                exceptionRecorderProvider.getIfAvailable(
                        space.br1440.platform.tracing.core.exception.ExceptionRecorder::secureDefault);

        if (!GlobalOpenTelemetry.isSet()) {
            throw new IllegalStateException(
                    "Controlled Platform Agent reported READY but GlobalOpenTelemetry is not registered");
        }
        log.debug("TracingRuntime: READY Controlled Platform Agent global");
        return new OtelTracingRuntime(GlobalOpenTelemetry.get(), policy, exceptionRecorder);
    }

    @Bean
    public TraceOperations traceOperations(TracingRuntime tracingImplementation) {
        TracingMode mode = tracingImplementation.state().mode();
        if (mode == TracingMode.DISABLED_BY_CONFIGURATION) {
            log.info("TraceOperations facade: DISABLED_BY_CONFIGURATION — NoopTraceOperations");
            return NoopTraceOperations.backedBy(tracingImplementation);
        }
        if (mode != TracingMode.ENABLED) {
            throw new IllegalStateException("Unexpected tracing runtime state after strict resolution: " + mode);
        }
        log.debug("TraceOperations facade: ENABLED — DefaultTraceOperations");
        return new DefaultTraceOperations(tracingImplementation);
    }

    @Bean
    public RequestIdentityBoundarySupport requestIdentityBoundarySupport(TracingRuntime tracingImplementation) {
        return new RequestIdentityBoundarySupport(tracingImplementation);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpanFactoryDiagnostics spanFactoryDiagnostics(TracingRuntime tracingImplementation) {
        return new SpanFactoryDiagnostics(tracingImplementation);
    }

    @Bean
    @ConditionalOnMissingBean
    public PlatformTracingJmxClient platformTracingJmxClient() {
        return new PlatformTracingJmxClient();
    }

    @Bean
    @ConditionalOnMissingBean
    public PlatformContextPropagation platformContextPropagation(TraceOperations traceOperations) {
        if (traceOperations instanceof NoopTraceOperations) {
            log.debug("PlatformContextPropagation: используется NoOp (OpenTelemetry в degraded mode)");
            return NoOpPlatformContextPropagation.INSTANCE;
        }
        log.debug("PlatformContextPropagation: используется OtelPlatformContextPropagation");
        return new OtelPlatformContextPropagation();
    }
}
