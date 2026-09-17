package space.br1440.platform.tracing.autoconfigure.metrics;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidationMetrics;

import io.micrometer.core.instrument.MeterRegistry;

final class MicrometerSpanAttributeValidationMetrics implements SpanAttributeValidationMetrics {

    private static final String VIOLATIONS = "platform.tracing.span_attributes.validation.violations";

    private final MeterRegistry registry;

    public MicrometerSpanAttributeValidationMetrics(@Nonnull MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void violation(String ruleId, String builderName) {
        registry.counter(VIOLATIONS, "rule", ruleId, "builder", builderName).increment();
    }
}
