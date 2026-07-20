package space.br1440.platform.tracing.autoconfigure;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
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
@ConditionalOnProperty(prefix = TracingProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TracingProperties.class)
public class TracingCoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TracingCoreAutoConfiguration.class);

    @Bean
    public static BeanFactoryPostProcessor rejectCustomTracingRuntimeBeans() {
        return beanFactory -> {
            String[] runtimeBeans = beanFactory.getBeanNamesForType(TracingRuntime.class, false, false);
            for (String beanName : runtimeBeans) {
                if (!"tracingImplementation".equals(beanName)) {
                    if (beanFactory instanceof BeanDefinitionRegistry registry
                            && registry.containsBeanDefinition(beanName)) {
                        throw new IllegalStateException(
                                "Custom TracingRuntime bean is not a supported production extension point: "
                                        + beanName);
                    }
                }
            }
        };
    }

    @Bean
    public SdkModeDiagnostics platformSdkModeDiagnostics(
            org.springframework.beans.factory.ObjectProvider<OpenTelemetry> openTelemetryProvider,
            TracingProperties properties) {
        boolean agentPresent = OtelAgentDetector.isAgentPresent();
        boolean userBeanPresent = openTelemetryProvider.getIfAvailable() != null;
        boolean configuredDisabled = properties.getSdk().getMode() == SdkMode.DISABLED;
        AgentExtensionDescriptor extension = new AgentExtensionObserver().observe(
                configuredDisabled, agentPresent, userBeanPresent);
        boolean agentReady = extension.state() == AgentRuntimeState.AGENT_READY;
        boolean agentRuntimePresent = agentPresent || extension.readinessEndpointPresent();
        boolean globalFunctional = !userBeanPresent
                && !agentRuntimePresent
                && isGlobalFunctional();

        SdkMode resolved = SdkModeResolver.resolve(
                properties.getSdk().getMode(),
                new SdkModeResolver.Inputs(
                        agentReady, agentRuntimePresent, globalFunctional, userBeanPresent));

        if (extension.state() != AgentRuntimeState.AGENT_READY
                && extension.state() != AgentRuntimeState.DISABLED
                && extension.state() != AgentRuntimeState.AGENT_MISSING) {
            log.error("Platform tracing Agent runtime is not ready: state={}, failureCode={}, failureMessage={}",
                    extension.state(), extension.failureCode(), extension.failureMessage());
        }
        log.info("Платформенная трассировка: SDK mode={} (runtimeState={}, agentMarker={}, "
                        + "globalFunctional={}, userOpenTelemetryBean={})",
                resolved, extension.state(), agentPresent, globalFunctional, userBeanPresent);
        return new SdkModeDiagnostics(resolved, agentPresent, extension.state(), extension);
    }

    @Bean
    public TracingRuntime tracingImplementation(
            org.springframework.beans.factory.ObjectProvider<OpenTelemetry> openTelemetryProvider,
            org.springframework.beans.factory.ObjectProvider<
                    space.br1440.platform.tracing.core.semconv.policy.AttributePolicy> policyProvider,
            org.springframework.beans.factory.ObjectProvider<
                    space.br1440.platform.tracing.core.exception.ExceptionRecorder> exceptionRecorderProvider,
            org.springframework.beans.factory.ObjectProvider<PlatformTracingMetrics> metricsProvider,
            SdkModeDiagnostics sdkModeDiagnostics) {
        TracingRuntime base = resolveTracingRuntime(
                openTelemetryProvider,
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
            org.springframework.beans.factory.ObjectProvider<OpenTelemetry> openTelemetryProvider,
            org.springframework.beans.factory.ObjectProvider<
                    space.br1440.platform.tracing.core.semconv.policy.AttributePolicy> policyProvider,
            org.springframework.beans.factory.ObjectProvider<
                    space.br1440.platform.tracing.core.exception.ExceptionRecorder> exceptionRecorderProvider,
            SdkModeDiagnostics sdkModeDiagnostics) {
        if (sdkModeDiagnostics.mode() == SdkMode.DISABLED) {
            log.info("platform.tracing.sdk.mode=DISABLED — TracingRuntime DISABLED_BY_CONFIGURATION");
            return NoOpTracingRuntime.disabledByConfiguration("platform.tracing.sdk.mode=DISABLED");
        }
        if (sdkModeDiagnostics.mode() == SdkMode.STARTER) {
            String reason = "Platform Agent runtime is not ready: " + sdkModeDiagnostics.runtimeState();
            log.error(reason);
            return NoOpTracingRuntime.unavailable(reason);
        }
        if (sdkModeDiagnostics.mode() == SdkMode.AGENT
                && sdkModeDiagnostics.runtimeState() != AgentRuntimeState.AGENT_READY) {
            throw new IllegalStateException(
                    "AGENT mode resolved without READY compatible platform extension: "
                            + sdkModeDiagnostics.runtimeState());
        }

        space.br1440.platform.tracing.core.semconv.policy.AttributePolicy policy =
                policyProvider.getIfAvailable(space.br1440.platform.tracing.core.semconv.policy.AttributePolicy::new);
        space.br1440.platform.tracing.core.exception.ExceptionRecorder exceptionRecorder =
                exceptionRecorderProvider.getIfAvailable(
                        space.br1440.platform.tracing.core.exception.ExceptionRecorder::secureDefault);

        OpenTelemetry openTelemetry = openTelemetryProvider.getIfAvailable();
        if (openTelemetry != null) {
            log.debug("TracingRuntime: OpenTelemetry bean from application context");
            return new OtelTracingRuntime(openTelemetry, policy, exceptionRecorder);
        }
        OpenTelemetry global;
        try {
            global = GlobalOpenTelemetry.get();
        } catch (RuntimeException e) {
            log.warn("TracingRuntime: GlobalOpenTelemetry unavailable ({}); UNAVAILABLE mode",
                    e.getMessage());
            return NoOpTracingRuntime.unavailable("GlobalOpenTelemetry unavailable: " + e.getMessage());
        }
        if (!isFunctional(global)) {
            log.info("TracingRuntime: GlobalOpenTelemetry no-op; UNAVAILABLE mode");
            return NoOpTracingRuntime.unavailable("GlobalOpenTelemetry not functional");
        }
        log.debug("TracingRuntime: functional GlobalOpenTelemetry for SDK mode={}", sdkModeDiagnostics.mode());
        return new OtelTracingRuntime(global, policy, exceptionRecorder);
    }

    @Bean
    public TraceOperations traceOperations(TracingRuntime tracingImplementation) {
        TracingMode mode = tracingImplementation.state().mode();
        if (mode != TracingMode.ENABLED) {
            log.info("TraceOperations facade: {} — NoopTraceOperations", mode);
            return NoopTraceOperations.backedBy(tracingImplementation);
        }
        log.debug("TraceOperations facade: ENABLED — DefaultTraceOperations");
        return new DefaultTraceOperations(tracingImplementation);
    }

    private static boolean isGlobalFunctional() {
        try {
            return GlobalOpenTelemetry.isSet()
                    && isFunctional(GlobalOpenTelemetry.getOrNoop());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Probes whether the supplied {@link OpenTelemetry} instance can create valid spans.
     * <p>
     * Creates a short-lived probe span ({@code __probe}), ends it immediately, and checks
     * {@code SpanContext.isValid()}. Depending on SDK/exporter configuration, the probe span may
     * appear in local exporters; this side effect is expected (B09).
     */
    private static boolean isFunctional(OpenTelemetry openTelemetry) {
        Span probe = openTelemetry.getTracer("space.br1440.platform.tracing.probe")
                .spanBuilder("__probe")
                .startSpan();
        try {
            return probe.getSpanContext().isValid();
        } finally {
            probe.end();
        }
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
