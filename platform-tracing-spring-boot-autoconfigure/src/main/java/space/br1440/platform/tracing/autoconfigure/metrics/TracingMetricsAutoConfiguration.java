package space.br1440.platform.tracing.autoconfigure.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import space.br1440.platform.tracing.api.semconv.SemconvValidationMode;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.integrations.aop.TracingAopAutoConfiguration;
import space.br1440.platform.tracing.otel.semconv.policy.SemconvMetrics;

import io.micrometer.core.instrument.MeterRegistry;

@AutoConfiguration
@AutoConfigureBefore(value = TracingAopAutoConfiguration.class,
        name = {
                "space.br1440.platform.tracing.autoconfigure.servlet.ServletTracingAutoConfiguration",
                "space.br1440.platform.tracing.autoconfigure.reactive.ReactiveTracingAutoConfiguration"
        })
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean({MeterRegistry.class, TracingProperties.class})
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
    public MeterBinder platformSemanticValidationDisabledBinder(TracingProperties properties) {
        return registry -> Gauge.builder(
                        "platform.tracing.semantic.validation.disabled",
                        properties,
                        TracingMetricsAutoConfiguration::semanticValidationDisabledValue)
                .description("1 if platform.tracing.semantic.validation-mode=DISABLED, otherwise 0")
                .register(registry);
    }

    private static double semanticValidationDisabledValue(TracingProperties properties) {
        SemconvValidationMode validationMode = properties.getSemantic().getValidationMode();
        return (validationMode == SemconvValidationMode.DISABLED) ? 1 : 0;
    }

    @Bean
    @ConditionalOnBean(PlatformTracingJmxClient.class)
    @ConditionalOnMissingBean(PlatformTracingSamplerMetricsBinder.class)
    public MeterBinder platformTracingSamplerMetricsBinder(PlatformTracingJmxClient platformTracingJmxClient) {
        return new PlatformTracingSamplerMetricsBinder(platformTracingJmxClient);
    }

    @Bean
    @ConditionalOnBean(PlatformTracingJmxClient.class)
    @ConditionalOnMissingBean(PlatformTracingSafeWrapperMetricsBinder.class)
    public MeterBinder platformTracingSafeWrapperMetricsBinder(PlatformTracingJmxClient platformTracingJmxClient) {
        return new PlatformTracingSafeWrapperMetricsBinder(platformTracingJmxClient);
    }

    @Bean
    @ConditionalOnBean(PlatformTracingJmxClient.class)
    @ConditionalOnMissingBean(PlatformTracingConfigMetricsBinder.class)
    public MeterBinder platformTracingConfigMetricsBinder(PlatformTracingJmxClient platformTracingJmxClient) {
        return new PlatformTracingConfigMetricsBinder(platformTracingJmxClient);
    }
}
