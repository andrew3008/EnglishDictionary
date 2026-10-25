package space.br1440.platform.tracing.autoconfigure.metrics;

import java.time.Duration;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceException;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.DiagnosticsObservation;
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
    private final CoalescingJmxObservationSource<SamplingObservation> samplingObservationSource;
    private final CoalescingJmxObservationSource<ScrubbingObservation> scrubbingObservationSource;
    private final CoalescingJmxObservationSource<ValidationObservation> validationObservationSource;
    private final CoalescingJmxObservationSource<ExportObservation> exportObservationSource;
    private final CoalescingJmxObservationSource<ProcessorMetricsObservation> processorMetricsObservationSource;
    private final CoalescingJmxObservationSource<DiagnosticsObservation> diagnosticsObservationSource;

    PlatformTracingJmxDomainHealthMetricsBinder(PlatformTracingJmxClient client) {
        this(client, System::nanoTime);
    }

    PlatformTracingJmxDomainHealthMetricsBinder(PlatformTracingJmxClient client,
                                                MonotonicJmxCounterGroupBridge.Ticker ticker) {
        this.client = client;

        this.samplingObservationSource = new CoalescingJmxObservationSource<>(
                () -> client.sampling().observation(),
                ticker
        );

        this.scrubbingObservationSource = new CoalescingJmxObservationSource<>(
                () -> client.scrubbing().observation(),
                ticker
        );

        this.validationObservationSource = new CoalescingJmxObservationSource<>(
                () -> client.validation().observation(),
                ticker
        );

        this.exportObservationSource = new CoalescingJmxObservationSource<>(
                () -> client.export().observation(),
                ticker
        );

        this.processorMetricsObservationSource = new CoalescingJmxObservationSource<>(
                () -> client.processorMetrics().observation(),
                ticker
        );

        this.diagnosticsObservationSource = new CoalescingJmxObservationSource<>(
                () -> client.diagnostics().observation(),
                ticker
        );
    }

    @Override
    public void bindTo(@Nonnull MeterRegistry registry) {
        bindDomain(registry, "sampling",
                c -> registeredValue(c.sampling().registration()),
                c -> samplingObservationSource.get().isPresent() ? 1.0 : 0.0,
                c -> c.sampling().lastSuccessAge()
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::freshnessValue)
                        .orElse(Double.NaN));
        bindOwner(registry, "sampling", "sampler",
                c -> samplingObservationSource.get()
                        .map(SamplingObservation::decisionCounts)
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::ownerPresentValue)
                        .orElse(Double.NaN));

        bindDomain(registry, "scrubbing",
                c -> registeredValue(c.scrubbing().registration()),
                c -> scrubbingObservationSource.get().isPresent() ? 1.0 : 0.0,
                c -> c.scrubbing().lastSuccessAge()
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::freshnessValue)
                        .orElse(Double.NaN));
        bindOwner(registry, "scrubbing", "scrubbing",
                c -> scrubbingObservationSource.get()
                        .map(ScrubbingObservation::owner)
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::ownerPresentValue)
                        .orElse(Double.NaN));

        bindDomain(registry, "validation",
                c -> registeredValue(c.validation().registration()),
                c -> validationObservationSource.get().isPresent() ? 1.0 : 0.0,
                c -> c.validation().lastSuccessAge()
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::freshnessValue)
                        .orElse(Double.NaN));
        bindOwner(registry, "validation", "validation",
                c -> validationObservationSource.get()
                        .map(ValidationObservation::owner)
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::ownerPresentValue)
                        .orElse(Double.NaN));

        bindDomain(registry, "export",
                c -> registeredValue(c.export().registration()),
                c -> exportObservationSource.get().isPresent() ? 1.0 : 0.0,
                c -> c.export().lastSuccessAge()
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::freshnessValue)
                        .orElse(Double.NaN));
        bindOwner(registry, "export", "processor",
                c -> exportObservationSource.get()
                        .map(ExportObservation::processor)
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::ownerPresentValue)
                        .orElse(Double.NaN));
        bindOwner(registry, "export", "exporter",
                c -> exportObservationSource.get()
                        .map(ExportObservation::exporter)
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::ownerPresentValue)
                        .orElse(Double.NaN));

        bindDomain(registry, "processor_metrics",
                c -> registeredValue(c.processorMetrics().registration()),
                c -> processorMetricsObservationSource.get().isPresent() ? 1.0 : 0.0,
                c -> c.processorMetrics().lastSuccessAge()
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::freshnessValue)
                        .orElse(Double.NaN));
        bindOwner(registry, "processor_metrics", "span_timeout",
                c -> processorMetricsObservationSource.get()
                        .map(ProcessorMetricsObservation::spanTimeout)
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::ownerPresentValue)
                        .orElse(Double.NaN));
        bindOwner(registry, "processor_metrics", "composite",
                c -> processorMetricsObservationSource.get()
                        .map(ProcessorMetricsObservation::composite)
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::ownerPresentValue)
                        .orElse(Double.NaN));
        bindOwner(registry, "processor_metrics", "metrics",
                c -> processorMetricsObservationSource.get()
                        .map(ProcessorMetricsObservation::metrics)
                        .map(PlatformTracingJmxDomainHealthMetricsBinder::ownerPresentValue)
                        .orElse(Double.NaN));

        bindDomain(registry, "diagnostics",
                c -> registeredValue(c.diagnostics().registration()),
                c -> diagnosticsObservationSource.get().isPresent() ? 1.0 : 0.0,
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

        Gauge.builder(READABLE_METRIC, client, c -> readOrDefault(c, readable, 0.0))
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
        Gauge.builder(OWNER_PRESENT_METRIC, client, c -> readOrDefault(c, ownerPresent, Double.NaN))
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

    private static double readOrDefault(PlatformTracingJmxClient client,
                                        DomainMetricValue metric,
                                        double defaultValue) {
        try {
            return metric.read(client);
        } catch (AgentReadinessCoherenceException ignored) {
            return defaultValue;
        }
    }

    @FunctionalInterface
    private interface DomainMetricValue {
        double read(PlatformTracingJmxClient client);
    }
}
