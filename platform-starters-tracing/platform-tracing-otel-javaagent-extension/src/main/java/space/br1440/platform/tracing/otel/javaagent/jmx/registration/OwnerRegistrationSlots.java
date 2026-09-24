package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.processor.SpanLimitsMetricsProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.dropoldest.PlatformDropOldestExportSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.spantimeout.SpanTimeoutProcessor;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

final class OwnerRegistrationSlots {

    private final OwnerRegistrationSlot<CompositeSampler> compositeSampler = new OwnerRegistrationSlot<>();
    private final OwnerRegistrationSlot<SpanTimeoutProcessor> spanTimeout = new OwnerRegistrationSlot<>();
    private final OwnerRegistrationSlot<PlatformCompositeSpanProcessor> composite = new OwnerRegistrationSlot<>();
    private final OwnerRegistrationSlot<SpanLimitsMetricsProcessor> metrics = new OwnerRegistrationSlot<>();
    private final OwnerRegistrationSlot<ScrubbingRuntime> scrubbing = new OwnerRegistrationSlot<>();
    private final OwnerRegistrationSlot<ValidatingSpanProcessor> validating = new OwnerRegistrationSlot<>();
    private final OwnerRegistrationSlot<PlatformDropOldestExportSpanProcessor> exportProcessor = new OwnerRegistrationSlot<>();
    private final OwnerRegistrationSlot<SafeSpanExporter> safeExporter = new OwnerRegistrationSlot<>();

    OwnerRegistrationSlot<CompositeSampler> compositeSampler() {
        return compositeSampler;
    }

    OwnerRegistrationSlot<SpanTimeoutProcessor> spanTimeout() {
        return spanTimeout;
    }

    OwnerRegistrationSlot<PlatformCompositeSpanProcessor> composite() {
        return composite;
    }

    OwnerRegistrationSlot<SpanLimitsMetricsProcessor> metrics() {
        return metrics;
    }

    OwnerRegistrationSlot<ScrubbingRuntime> scrubbing() {
        return scrubbing;
    }

    OwnerRegistrationSlot<ValidatingSpanProcessor> validating() {
        return validating;
    }

    OwnerRegistrationSlot<PlatformDropOldestExportSpanProcessor> exportProcessor() {
        return exportProcessor;
    }

    OwnerRegistrationSlot<SafeSpanExporter> safeExporter() {
        return safeExporter;
    }
}
