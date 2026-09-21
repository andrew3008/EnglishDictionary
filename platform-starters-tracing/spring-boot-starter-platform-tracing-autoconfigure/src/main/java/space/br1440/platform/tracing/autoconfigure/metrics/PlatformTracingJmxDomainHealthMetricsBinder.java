package space.br1440.platform.tracing.autoconfigure.metrics;

import java.time.Duration;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export.ExportObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorMetricsObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.sampling.SamplingObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.scrubbing.ScrubbingObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxMBeanRegistrationState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.validation.ValidationObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

final class PlatformTracingJmxDomainHealthMetricsBinder implements MeterBinder {

    private static final String REGISTERED_METRIC = "platform.tracing.jmx.domain.registered";
    private static final String READABLE_METRIC = "platform.tracing.jmx.domain.readable";
    private static final String FRESHNESS_METRIC = "platform.tracing.jmx.domain.last_success_age_seconds";
    private static final String OWNER_PRESENT_METRIC = "platform.tracing.jmx.owner.present";

    private final PlatformTracingJmxClient client;

    PlatformTracingJmxDomainHealthMetricsBinder(PlatformTracingJmxClient client) {
        this.client = client;
    }

    @Override
    public void bindTo(@Nonnull MeterRegistry registry) {
        bindDomain(registry, "sampling",
                c -> registeredValue(c.sampling().registration()),
                c -> c.sampling().observation().isPresent() ? 1.0 : 0.0,
                c -> c.sampling().lastSuccessAge()
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::freshnessValue)
                        .orElse(Double.NaN));
        bindOwner(registry, "sampling", "sampler",
                c -> c.sampling().observation()
                        .map(SamplingObservation::decisionCounts)
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::ownerPresentValue)
                        .orElse(Double.NaN));

        bindDomain(registry, "scrubbing",
                c -> registeredValue(c.scrubbing().registration()),
                c -> c.scrubbing().observation().isPresent() ? 1.0 : 0.0,
                c -> c.scrubbing().lastSuccessAge()
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::freshnessValue)
                        .orElse(Double.NaN));
        bindOwner(registry, "scrubbing", "scrubbing",
                c -> c.scrubbing().observation()
                        .map(ScrubbingObservation::owner)
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::ownerPresentValue)
                        .orElse(Double.NaN));

        bindDomain(registry, "validation",
                c -> registeredValue(c.validation().registration()),
                c -> c.validation().observation().isPresent() ? 1.0 : 0.0,
                c -> c.validation().lastSuccessAge()
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::freshnessValue)
                        .orElse(Double.NaN));
        bindOwner(registry, "validation", "validation",
                c -> c.validation().observation()
                        .map(ValidationObservation::owner)
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::ownerPresentValue)
                        .orElse(Double.NaN));

        bindDomain(registry, "export",
                c -> registeredValue(c.export().registration()),
                c -> c.export().observation().isPresent() ? 1.0 : 0.0,
                c -> c.export().lastSuccessAge()
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::freshnessValue)
                        .orElse(Double.NaN));
        bindOwner(registry, "export", "processor",
                c -> c.export().observation()
                        .map(ExportObservation::processor)
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::ownerPresentValue)
                        .orElse(Double.NaN));
        bindOwner(registry, "export", "exporter",
                c -> c.export().observation()
                        .map(ExportObservation::exporter)
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::ownerPresentValue)
                        .orElse(Double.NaN));

        bindDomain(registry, "processor_metrics",
                c -> registeredValue(c.processorMetrics().registration()),
                c -> c.processorMetrics().observation().isPresent() ? 1.0 : 0.0,
                c -> c.processorMetrics().lastSuccessAge()
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::freshnessValue)
                        .orElse(Double.NaN));
        bindOwner(registry, "processor_metrics", "watchdog",
                c -> c.processorMetrics().observation()
                        .map(ProcessorMetricsObservation::spanTimeout)
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::ownerPresentValue)
                        .orElse(Double.NaN));
        bindOwner(registry, "processor_metrics", "composite",
                c -> c.processorMetrics().observation()
                        .map(ProcessorMetricsObservation::composite)
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::ownerPresentValue)
                        .orElse(Double.NaN));
        bindOwner(registry, "processor_metrics", "metrics",
                c -> c.processorMetrics().observation()
                        .map(ProcessorMetricsObservation::metrics)
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::ownerPresentValue)
                        .orElse(Double.NaN));

        bindDomain(registry, "diagnostics",
                c -> registeredValue(c.diagnostics().registration()),
                c -> c.diagnostics().observation().isPresent() ? 1.0 : 0.0,
                c -> c.diagnostics().lastSuccessAge()
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::freshnessValue)
                        .orElse(Double.NaN));
    }

    private void bindDomain(MeterRegistry registry,
                            String domain,
                            DomainMetricValue registration,
                            DomainMetricValue readable,
                            DomainMetricValue lastSuccessAge) {
        Gauge.builder(REGISTERED_METRIC, client, registration::read)
                .tags("domain", domain)
                .description("MBean registration tri-state: 1 registered, 0 not registered, NaN unknown")
                .register(registry);

        Gauge.builder(READABLE_METRIC, client, readable::read)
                .tags("domain", domain)
                .description("1 if the last JMX read for this domain decoded successfully, otherwise 0")
                .register(registry);

        Gauge.builder(FRESHNESS_METRIC, client, lastSuccessAge::read)
                .tags("domain", domain)
                .description("Seconds since the last successful decode of this domain, NaN before the first success")
                .register(registry);
    }

    private void bindOwner(MeterRegistry registry,
                           String domain,
                           String owner,
                           DomainMetricValue ownerPresent) {
        Gauge.builder(OWNER_PRESENT_METRIC, client, ownerPresent::read)
                .tags("domain", domain, "owner", owner)
                .description("Owner presence tri-state: 1 present, 0 absent, NaN when the domain itself is unreadable")
                .register(registry);
    }

    private static double registeredValue(JmxMBeanRegistrationState state) {
        return switch (state) {
            case REGISTERED -> 1.0;
            case NOT_REGISTERED -> 0.0;
            case UNKNOWN -> Double.NaN;
        };
    }

    private static double freshnessValue(Duration lastSuccessAge) {
        return lastSuccessAge.toNanos() / 1_000_000_000.0;
    }

    private static double ownerPresentValue(JmxOwnerState<?> ownerState) {
        return (ownerState instanceof JmxOwnerState.Present<?>) ? 1.0 : 0.0;
    }

    @FunctionalInterface
    private interface DomainMetricValue {
        double read(PlatformTracingJmxClient client);
    }
}
