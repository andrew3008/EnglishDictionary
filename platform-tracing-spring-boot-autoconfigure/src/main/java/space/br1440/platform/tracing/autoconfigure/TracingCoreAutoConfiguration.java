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
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.api.propagation.PlatformContextPropagation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.ManualTracingDiagnostics;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.metrics.MeteredTracingRuntime;
import space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingMetrics;
import space.br1440.platform.tracing.autoconfigure.support.OtelAgentDetector;
import space.br1440.platform.tracing.autoconfigure.support.SdkMode;
import space.br1440.platform.tracing.autoconfigure.support.SdkModeDiagnostics;
import space.br1440.platform.tracing.autoconfigure.support.SdkModeResolver;
import space.br1440.platform.tracing.core.facade.DefaultPlatformTracing;
import space.br1440.platform.tracing.core.propagation.NoOpPlatformContextPropagation;
import space.br1440.platform.tracing.core.facade.NoOpPlatformTracing;
import space.br1440.platform.tracing.core.propagation.OtelPlatformContextPropagation;
import space.br1440.platform.tracing.core.runtime.otel.OtelTracingRuntime;
import space.br1440.platform.tracing.core.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.core.runtime.TracingRuntime;
import space.br1440.platform.tracing.core.runtime.state.TracingMode;

/**
 * Базовая авто-конфигурация платформенного модуля трассировки.
 * <p>
 * Slice 2: registers {@link TracingRuntime} as the internal span-creation boundary and
 * {@link PlatformTracing} as a thin facade over it.
 * <p>
 * <b>Extension point note (B10):</b> if the application supplies its own
 * {@code TracingRuntime} bean (via {@code @ConditionalOnMissingBean}), platform
 * autoconfiguration will <b>not</b> wrap it with {@link MeteredTracingRuntime}. This is
 * an advanced extension path, not the default application wiring, and is covered by
 * {@code BeanTopologyTest#userPrimaryTracingRuntime_replacesDefaultWithoutHiddenBypass}.
 */
@AutoConfiguration
@ConditionalOnClass(OpenTelemetry.class)
@ConditionalOnProperty(prefix = TracingProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TracingProperties.class)
public class TracingCoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TracingCoreAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public SdkModeDiagnostics platformSdkModeDiagnostics(
            org.springframework.beans.factory.ObjectProvider<OpenTelemetry> openTelemetryProvider,
            TracingProperties properties) {
        boolean agentPresent = OtelAgentDetector.isAgentPresent();
        boolean userBeanPresent = openTelemetryProvider.getIfAvailable() != null;
        boolean globalFunctional = !userBeanPresent && isGlobalFunctional();

        SdkMode resolved = SdkModeResolver.resolve(
                properties.getSdk().getMode(),
                new SdkModeResolver.Inputs(agentPresent, globalFunctional, userBeanPresent));

        log.info("Платформенная трассировка: SDK mode={} (agentDetected={}, globalFunctional={}, userOpenTelemetryBean={})",
                resolved, agentPresent, globalFunctional, userBeanPresent);
        return new SdkModeDiagnostics(resolved, agentPresent);
    }

    @Bean
    @ConditionalOnMissingBean
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
        log.debug("TracingRuntime: GlobalOpenTelemetry (agent)");
        return new OtelTracingRuntime(global, policy, exceptionRecorder);
    }

    @Bean
    @ConditionalOnMissingBean
    public PlatformTracing platformTracing(TracingRuntime tracingImplementation) {
        TracingMode mode = tracingImplementation.state().mode();
        if (mode != TracingMode.ENABLED) {
            log.info("PlatformTracing facade: {} — NoOpPlatformTracing", mode);
            return NoOpPlatformTracing.backedBy(tracingImplementation);
        }
        log.debug("PlatformTracing facade: ENABLED — DefaultPlatformTracing");
        return new DefaultPlatformTracing(tracingImplementation);
    }

    private static boolean isGlobalFunctional() {
        try {
            return isFunctional(GlobalOpenTelemetry.get());
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
    public ManualTracingDiagnostics manualTracingDiagnostics(TracingRuntime tracingImplementation) {
        return new ManualTracingDiagnostics(tracingImplementation);
    }

    @Bean
    @ConditionalOnMissingBean
    public PlatformTracingJmxClient platformTracingJmxClient() {
        return new PlatformTracingJmxClient();
    }

    @Bean
    @ConditionalOnMissingBean
    public PlatformContextPropagation platformContextPropagation(PlatformTracing platformTracing) {
        if (platformTracing instanceof NoOpPlatformTracing) {
            log.debug("PlatformContextPropagation: используется NoOp (OpenTelemetry в degraded mode)");
            return NoOpPlatformContextPropagation.INSTANCE;
        }
        log.debug("PlatformContextPropagation: используется OtelPlatformContextPropagation");
        return new OtelPlatformContextPropagation();
    }
}
