package space.br1440.platform.tracing.autoconfigure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapPropertyNames;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.integrations.aop.TracingAopAutoConfiguration;
import space.br1440.platform.tracing.api.semconv.SemconvValidationMode;
import space.br1440.platform.tracing.otel.semconv.policy.SemconvMetrics;

/**
 * Авто-конфигурация самонаблюдательных метрик платформенного модуля трассировки.
 * <p>
 * Активна только при наличии в контексте бина {@link MeterRegistry}, который поставляется модулем
 * {@code spring-boot-starter-platform-metrics}. Регистрирует {@link PlatformTracingMetrics} и
 * вспомогательные MeterBinder'ы.
 * <p>
 * Slice 6: when {@link MeterRegistry} is present, {@link TracingCoreAutoConfiguration} wraps the
 * active {@link space.br1440.platform.tracing.otel.runtime.TracingRuntime} with
 * {@link MeteredTracingRuntime}.
 */
@AutoConfiguration
@AutoConfigureBefore(value = TracingAopAutoConfiguration.class,
        name = {
                "space.br1440.platform.tracing.autoconfigure.servlet.ServletTracingAutoConfiguration",
                "space.br1440.platform.tracing.autoconfigure.reactive.ReactiveTracingAutoConfiguration"
        })
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnProperty(name = TracingBootstrapPropertyNames.MODE, havingValue = "AGENT", matchIfMissing = true)
public class TracingMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PlatformTracingMetrics platformTracingMetrics(MeterRegistry meterRegistry) {
        return new PlatformTracingMetrics(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(SemconvMetrics.class)
    public SemconvMetrics platformSemconvMetrics(MeterRegistry meterRegistry) {
        return new MicrometerSemconvMetrics(meterRegistry);
    }

    @Bean
    public io.micrometer.core.instrument.binder.MeterBinder platformSemanticValidationDisabledBinder(
            TracingProperties properties) {
        return registry -> {
            boolean disabled = properties.getSemantic().getValidationMode()
                    == SemconvValidationMode.DISABLED;
            registry.gauge("platform.tracing.semantic.validation.disabled", disabled ? 1 : 0);
        };
    }

    @Bean
    @ConditionalOnBean(PlatformTracingJmxClient.class)
    @ConditionalOnMissingBean
    public PlatformTracingSamplerMetricsBinder platformTracingSamplerMetricsBinder(PlatformTracingJmxClient platformTracingJmxClient) {
        return new space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingSamplerMetricsBinder(platformTracingJmxClient);
    }

    @Bean
    @ConditionalOnBean(PlatformTracingJmxClient.class)
    @ConditionalOnMissingBean
    public PlatformTracingSafeWrapperMetricsBinder platformTracingSafeWrapperMetricsBinder(
            PlatformTracingJmxClient platformTracingJmxClient) {
        return new PlatformTracingSafeWrapperMetricsBinder(platformTracingJmxClient);
    }

    @Bean
    @ConditionalOnBean(PlatformTracingJmxClient.class)
    @ConditionalOnMissingBean
    public PlatformTracingConfigMetricsBinder platformTracingConfigMetricsBinder(
            PlatformTracingJmxClient platformTracingJmxClient) {
        return new PlatformTracingConfigMetricsBinder(platformTracingJmxClient);
    }
}
