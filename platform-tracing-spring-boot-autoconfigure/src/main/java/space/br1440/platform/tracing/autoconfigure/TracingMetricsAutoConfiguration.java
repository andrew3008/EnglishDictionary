package space.br1440.platform.tracing.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.metrics.MeteredTracingRuntime;
import space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingMetrics;
import space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingSamplerMetricsBinder;

/**
 * Авто-конфигурация самонаблюдательных метрик платформенного модуля трассировки.
 * <p>
 * Активна только при наличии в контексте бина {@link MeterRegistry}, который поставляется модулем
 * {@code spring-boot-starter-platform-metrics}. Регистрирует {@link PlatformTracingMetrics} и
 * вспомогательные MeterBinder'ы.
 * <p>
 * Slice 6: when {@link MeterRegistry} is present, {@link TracingCoreAutoConfiguration} wraps the
 * active {@link space.br1440.platform.tracing.core.runtime.TracingRuntime} with
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
@ConditionalOnProperty(prefix = TracingProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
public class TracingMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PlatformTracingMetrics platformTracingMetrics(MeterRegistry meterRegistry) {
        return new PlatformTracingMetrics(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(space.br1440.platform.tracing.core.semconv.policy.SemconvMetrics.class)
    public space.br1440.platform.tracing.core.semconv.policy.SemconvMetrics platformSemconvMetrics(MeterRegistry meterRegistry) {
        return new space.br1440.platform.tracing.autoconfigure.metrics.MicrometerSemconvMetrics(meterRegistry);
    }

    @Bean
    public io.micrometer.core.instrument.binder.MeterBinder platformSemanticValidationDisabledBinder(
            TracingProperties properties) {
        return registry -> {
            boolean disabled = properties.getSemantic().getValidationMode()
                    == space.br1440.platform.tracing.api.semconv.ValidationMode.DISABLED;
            registry.gauge("platform.tracing.semantic.validation.disabled", disabled ? 1 : 0);
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public PlatformTracingSamplerMetricsBinder platformTracingSamplerMetricsBinder(PlatformTracingJmxClient platformTracingJmxClient) {
        return new space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingSamplerMetricsBinder(platformTracingJmxClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingSafeWrapperMetricsBinder platformTracingSafeWrapperMetricsBinder(PlatformTracingJmxClient platformTracingJmxClient) {
        return new space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingSafeWrapperMetricsBinder(platformTracingJmxClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingConfigMetricsBinder platformTracingConfigMetricsBinder(PlatformTracingJmxClient platformTracingJmxClient) {
        return new space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingConfigMetricsBinder(platformTracingJmxClient);
    }
}
