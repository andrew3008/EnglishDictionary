package space.br1440.platform.tracing.autoconfigure.metrics;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.otel.semconv.policy.SemconvMetrics;

import io.micrometer.core.instrument.MeterRegistry;

final class MicrometerSemconvMetrics implements SemconvMetrics {

    private static final String VIOLATIONS = "platform.tracing.semconv.violations";
    private static final String UNSAFE = "platform.tracing.unsafe_attributes";

    private final MeterRegistry registry;

    public MicrometerSemconvMetrics(@Nonnull MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void violation(String ruleId, String builderName) {
        registry.counter(VIOLATIONS, "rule", ruleId, "builder", builderName).increment();
    }

    @Override
    public void unsafeAttribute(String keyClass) {
        registry.counter(UNSAFE, "key_class", keyClass).increment();
    }
}
