package space.br1440.platform.tracing.otel.javaagent.jmx;

import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.processor.MetricsSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.PlatformDropOldestExportSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.ScrubbingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.SpanWatchdogProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

final class OwnerRegistrationSlots {

    private final OwnerRegistrationSlot<CompositeSampler> compositeSampler = new OwnerRegistrationSlot<>();
    private final OwnerRegistrationSlot<SpanWatchdogProcessor> watchdog = new OwnerRegistrationSlot<>();
    private final OwnerRegistrationSlot<PlatformCompositeSpanProcessor> composite = new OwnerRegistrationSlot<>();
    private final OwnerRegistrationSlot<MetricsSpanProcessor> metrics = new OwnerRegistrationSlot<>();
    private final OwnerRegistrationSlot<ScrubbingSpanProcessor> scrubbing = new OwnerRegistrationSlot<>();
    private final OwnerRegistrationSlot<ValidatingSpanProcessor> validating = new OwnerRegistrationSlot<>();
    private final OwnerRegistrationSlot<PlatformDropOldestExportSpanProcessor> exportProcessor = new OwnerRegistrationSlot<>();
    private final OwnerRegistrationSlot<SafeSpanExporter> safeExporter = new OwnerRegistrationSlot<>();

    OwnerRegistrationSlot<CompositeSampler> compositeSampler() {
        return compositeSampler;
    }

    OwnerRegistrationSlot<SpanWatchdogProcessor> watchdog() {
        return watchdog;
    }

    OwnerRegistrationSlot<PlatformCompositeSpanProcessor> composite() {
        return composite;
    }

    OwnerRegistrationSlot<MetricsSpanProcessor> metrics() {
        return metrics;
    }

    OwnerRegistrationSlot<ScrubbingSpanProcessor> scrubbing() {
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
