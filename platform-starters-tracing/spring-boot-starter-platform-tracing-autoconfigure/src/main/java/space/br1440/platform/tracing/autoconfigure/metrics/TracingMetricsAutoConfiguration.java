package space.br1440.platform.tracing.autoconfigure.metrics;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import space.br1440.platform.tracing.api.attributes.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.DiagnosticsObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.integrations.aop.TracingAopAutoConfiguration;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidationMetrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

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
    @ConditionalOnMissingBean(SpanAttributeValidationMetrics.class)
    public SpanAttributeValidationMetrics platformSpanAttributeValidationMetrics(MeterRegistry meterRegistry) {
        return new MicrometerSpanAttributeValidationMetrics(meterRegistry);
    }

    @Bean
    public MeterBinder platformSpanAttributeValidationDisabledBinder(TracingProperties properties) {
        return registry -> Gauge.builder(
                        "platform.tracing.span_attributes.validation.disabled",
                        properties,
                        TracingMetricsAutoConfiguration::spanAttributeValidationDisabledValue)
                .description("1 if platform.tracing.span-attributes.validation.level=DISABLED, otherwise 0")
                .register(registry);
    }

    private static double spanAttributeValidationDisabledValue(TracingProperties properties) {
        SpanAttributeValidationLevel level = properties.getSpanAttributes().getValidation().getLevel();
        return (level == SpanAttributeValidationLevel.DISABLED) ? 1 : 0;
    }

    @Bean
    @ConditionalOnBean(PlatformTracingJmxClient.class)
    @ConditionalOnMissingBean
    CoalescingJmxObservationSource<DiagnosticsObservation> platformTracingDiagnosticsCaptureSource(PlatformTracingJmxClient platformTracingJmxClient) {
        return new CoalescingJmxObservationSource<>(
                () -> platformTracingJmxClient.diagnostics().observation(), System::nanoTime);
    }

    @Bean
    @ConditionalOnBean(PlatformTracingJmxClient.class)
    @ConditionalOnMissingBean(PlatformTracingSamplerMetricsBinder.class)
    MeterBinder platformTracingSamplerMetricsBinder(PlatformTracingJmxClient platformTracingJmxClient,
                                                    CoalescingJmxObservationSource<DiagnosticsObservation> platformTracingDiagnosticsCaptureSource) {
        return new PlatformTracingSamplerMetricsBinder(platformTracingJmxClient, platformTracingDiagnosticsCaptureSource);
    }

    @Bean
    @ConditionalOnBean(PlatformTracingJmxClient.class)
    @ConditionalOnMissingBean(PlatformTracingSafeWrapperMetricsBinder.class)
    MeterBinder platformTracingSafeWrapperMetricsBinder(CoalescingJmxObservationSource<DiagnosticsObservation> platformTracingDiagnosticsCaptureSource) {
        return new PlatformTracingSafeWrapperMetricsBinder(platformTracingDiagnosticsCaptureSource);
    }

    @Bean
    @ConditionalOnBean(PlatformTracingJmxClient.class)
    @ConditionalOnMissingBean(PlatformTracingJmxDomainHealthMetricsBinder.class)
    public MeterBinder platformTracingJmxDomainHealthMetricsBinder(PlatformTracingJmxClient platformTracingJmxClient) {
        return new PlatformTracingJmxDomainHealthMetricsBinder(platformTracingJmxClient);
    }

    @Bean
    @ConditionalOnBean(PlatformTracingJmxClient.class)
    @ConditionalOnMissingBean(PlatformTracingExportMetricsBinder.class)
    public MeterBinder platformTracingExportMetricsBinder(PlatformTracingJmxClient platformTracingJmxClient) {
        return new PlatformTracingExportMetricsBinder(platformTracingJmxClient);
    }

    @Bean
    @ConditionalOnBean(PlatformTracingJmxClient.class)
    @ConditionalOnMissingBean(PlatformTracingScrubbingMetricsBinder.class)
    public MeterBinder platformTracingScrubbingMetricsBinder(PlatformTracingJmxClient platformTracingJmxClient) {
        return new PlatformTracingScrubbingMetricsBinder(platformTracingJmxClient);
    }

    @Bean
    @ConditionalOnBean(PlatformTracingJmxClient.class)
    @ConditionalOnMissingBean(PlatformTracingProcessorMetricsBinder.class)
    public MeterBinder platformTracingProcessorMetricsBinder(PlatformTracingJmxClient platformTracingJmxClient) {
        return new PlatformTracingProcessorMetricsBinder(platformTracingJmxClient);
    }
}
