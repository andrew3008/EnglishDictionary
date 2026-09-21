package space.br1440.platform.tracing.autoconfigure.metrics;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorCompositeValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorMetricsObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorMetricsValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorSpanTimeoutValues;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

final class PlatformTracingProcessorMetricsBinder implements MeterBinder {

    private static final String SPAN_TIMEOUT_METRIC_NAME = "platform.tracing.processor.watchdog.count";
    private static final String SPAN_TIMEOUT_ACTIVE_METRIC_NAME = "platform.tracing.processor.watchdog.active";
    private static final String SPAN_TIMEOUT_TRACKED_METRIC_NAME = "platform.tracing.processor.watchdog.tracked";
    private static final String SPAN_TIMEOUT_CAPACITY_METRIC_NAME = "platform.tracing.processor.watchdog.capacity";
    private static final String COMPOSITE_ERRORS_TOTAL_METRIC_NAME = "platform.tracing.processor.composite.errors_total";
    private static final String COMPOSITE_ERRORS_BY_NAME_METRIC_NAME = "platform.tracing.processor.composite.errors_by_name";
    private static final String METRICS_METRIC_NAME = "platform.tracing.processor.metrics.count";
    private static final String DOMAIN = "processor_metrics";
    private static final String SPAN_TIMEOUT_OWNER = "watchdog";
    private static final String COMPOSITE_OWNER = "composite";
    private static final String METRICS_OWNER = "metrics";

    private final PlatformTracingJmxClient client;
    private final CoalescingJmxObservationSource<ProcessorMetricsObservation> spanTimeoutObservationSource;
    private final MonotonicJmxCounterGroupBridge<ProcessorSpanTimeoutMetricKey> spanTimeoutBridge;
    private final MonotonicJmxCounterGroupBridge<ProcessorCompositeErrorKey> compositeBridge;
    private final MonotonicJmxCounterGroupBridge<ProcessorMetricsMetricKey> metricsBridge;

    PlatformTracingProcessorMetricsBinder(PlatformTracingJmxClient client) {
        this(client, System::nanoTime);
    }

    PlatformTracingProcessorMetricsBinder(PlatformTracingJmxClient client, MonotonicJmxCounterGroupBridge.Ticker ticker) {
        this.client = client;
        this.spanTimeoutObservationSource = new CoalescingJmxObservationSource<>(
                () -> client.processorMetrics().observation(), ticker);

        this.spanTimeoutBridge = new MonotonicJmxCounterGroupBridge<>(
                ProcessorSpanTimeoutMetricKey.class, this::spanTimeoutCounts, this::processorMetricsAgentInstanceId, ticker,
                MonotonicJmxCounterGroupBridge.DEFAULT_COALESCING_WINDOW_NANOS
        );

        this.compositeBridge = new MonotonicJmxCounterGroupBridge<>(
                ProcessorCompositeErrorKey.class, this::compositeCounts, this::processorMetricsAgentInstanceId, ticker,
                MonotonicJmxCounterGroupBridge.DEFAULT_COALESCING_WINDOW_NANOS
        );

        this.metricsBridge = new MonotonicJmxCounterGroupBridge<>(
                ProcessorMetricsMetricKey.class, this::metricsCounts, this::processorMetricsAgentInstanceId, ticker,
                MonotonicJmxCounterGroupBridge.DEFAULT_COALESCING_WINDOW_NANOS
        );
    }

    @Override
    public void bindTo(@Nonnull MeterRegistry registry) {
        for (ProcessorSpanTimeoutMetricKey key : ProcessorSpanTimeoutMetricKey.values()) {
            FunctionCounter.builder(SPAN_TIMEOUT_METRIC_NAME, spanTimeoutBridge, b -> b.count(key))
                    .tags("metric", key.metricTag())
                    .description("Cumulative counters of the span watchdog processor owner")
                    .register(registry);

            FunctionCounter.builder(MonotonicJmxCounterGroupBridge.ANOMALY_METRIC_NAME, spanTimeoutBridge, b -> b.anomalyCount(key))
                    .tags("domain", DOMAIN, "owner", SPAN_TIMEOUT_OWNER, "metric", key.metricTag())
                    .description("Backward-moving cumulative JMX values observed for a single owner generation")
                    .register(registry);
        }
        bindResets(registry, spanTimeoutBridge, SPAN_TIMEOUT_OWNER);

        Gauge.builder(SPAN_TIMEOUT_ACTIVE_METRIC_NAME, this, binder -> binder.activeCount(SpanTimeoutActiveScope.SPANS))
                .tags("scope", "spans")
                .description("Spans currently tracked by the span watchdog processor")
                .register(registry);
        Gauge.builder(SPAN_TIMEOUT_ACTIVE_METRIC_NAME, this, binder -> binder.activeCount(SpanTimeoutActiveScope.TRACES))
                .tags("scope", "traces")
                .description("Traces currently holding at least one live span in the watchdog")
                .register(registry);
        Gauge.builder(SPAN_TIMEOUT_TRACKED_METRIC_NAME, this, PlatformTracingProcessorMetricsBinder::trackedTraceCount)
                .tags("scope", "traces")
                .description("Trace records currently retained by the span watchdog, including epoch tombstones")
                .register(registry);
        Gauge.builder(SPAN_TIMEOUT_CAPACITY_METRIC_NAME, this, binder -> binder.capacity(SpanTimeoutCapacityScope.TRACES))
                .tags("scope", "traces")
                .description("Configured maximum number of trace records retained by the span watchdog")
                .register(registry);
        Gauge.builder(SPAN_TIMEOUT_CAPACITY_METRIC_NAME, this, binder -> binder.capacity(SpanTimeoutCapacityScope.SPANS))
                .tags("scope", "spans")
                .description("Configured global hard bound on tracked spans across all traces")
                .register(registry);
        Gauge.builder(SPAN_TIMEOUT_CAPACITY_METRIC_NAME, this, binder -> binder.capacity(SpanTimeoutCapacityScope.SPANS_PER_TRACE))
                .tags("scope", "spans_per_trace")
                .description("Configured per-trace fairness guard on tracked spans within a single trace")
                .register(registry);

        FunctionCounter.builder(COMPOSITE_ERRORS_TOTAL_METRIC_NAME, compositeBridge,
                        b -> b.count(ProcessorCompositeErrorKey.TOTAL))
                .description("Total processor pipeline errors observed across all processors")
                .register(registry);
        for (ProcessorCompositeErrorKey key : ProcessorCompositeErrorKey.values()) {
            if (key == ProcessorCompositeErrorKey.TOTAL) {
                continue;
            }
            FunctionCounter.builder(COMPOSITE_ERRORS_BY_NAME_METRIC_NAME, compositeBridge, b -> b.count(key))
                    .tags("processor", key.processorName())
                    .description("Processor pipeline errors observed for a specific processor")
                    .register(registry);
        }

        for (ProcessorCompositeErrorKey key : ProcessorCompositeErrorKey.values()) {
            FunctionCounter.builder(MonotonicJmxCounterGroupBridge.ANOMALY_METRIC_NAME, compositeBridge, b -> b.anomalyCount(key))
                    .tags("domain", DOMAIN, "owner", COMPOSITE_OWNER, "metric", key.metricTag())
                    .description("Backward-moving cumulative JMX values observed for a single owner generation")
                    .register(registry);
        }
        bindResets(registry, compositeBridge, COMPOSITE_OWNER);

        for (ProcessorMetricsMetricKey key : ProcessorMetricsMetricKey.values()) {
            FunctionCounter.builder(METRICS_METRIC_NAME, metricsBridge, b -> b.count(key))
                    .tags("metric", key.metricTag())
                    .description("Cumulative counters of the metrics span processor owner")
                    .register(registry);

            FunctionCounter.builder(MonotonicJmxCounterGroupBridge.ANOMALY_METRIC_NAME, metricsBridge, b -> b.anomalyCount(key))
                    .tags("domain", DOMAIN, "owner", METRICS_OWNER, "metric", key.metricTag())
                    .description("Backward-moving cumulative JMX values observed for a single owner generation")
                    .register(registry);
        }
        bindResets(registry, metricsBridge, METRICS_OWNER);
    }

    private static <K extends Enum<K>> void bindResets(MeterRegistry registry,
                                                       MonotonicJmxCounterGroupBridge<K> bridge,
                                                       String owner) {
        for (MonotonicJmxCounterGroupBridge.ResetCause cause : MonotonicJmxCounterGroupBridge.ResetCause.values()) {
            FunctionCounter.builder(MonotonicJmxCounterGroupBridge.RESET_METRIC_NAME, bridge, b -> b.resetCount(cause))
                    .tags("domain", DOMAIN, "owner", owner, "cause", cause.tag())
                    .description("Source identity resets observed for a cumulative JMX counter group")
                    .register(registry);
        }
    }

    private Optional<JmxOwnerState<Map<ProcessorSpanTimeoutMetricKey, Long>>> spanTimeoutCounts() {
        return client.processorMetrics().observation()
                .map(ProcessorMetricsObservation::spanTimeout)
                .map(PlatformTracingProcessorMetricsBinder::toSpanTimeoutKeyedState);
    }

    private Optional<JmxOwnerState<Map<ProcessorCompositeErrorKey, Long>>> compositeCounts() {
        return client.processorMetrics().observation()
                .map(ProcessorMetricsObservation::composite)
                .map(PlatformTracingProcessorMetricsBinder::toCompositeKeyedState);
    }

    private Optional<JmxOwnerState<Map<ProcessorMetricsMetricKey, Long>>> metricsCounts() {
        return client.processorMetrics().observation()
                .map(ProcessorMetricsObservation::metrics)
                .map(PlatformTracingProcessorMetricsBinder::toMetricsKeyedState);
    }

    private String processorMetricsAgentInstanceId() {
        return client.processorMetrics().observation()
                .map(observation -> observation.capture().agentInstanceId())
                .orElseGet(MonotonicJmxCounterGroupBridge.LOCAL_PROCESS_IDENTITY);
    }

    static JmxOwnerState<Map<ProcessorSpanTimeoutMetricKey, Long>> toSpanTimeoutKeyedState(JmxOwnerState<ProcessorSpanTimeoutValues> state) {
        if (!(state instanceof JmxOwnerState.Present<ProcessorSpanTimeoutValues>(
                ProcessorSpanTimeoutValues values, long generation
        ))) {
            return new JmxOwnerState.Absent<>(state.generation());
        }

        Map<ProcessorSpanTimeoutMetricKey, Long> keyed = new EnumMap<>(ProcessorSpanTimeoutMetricKey.class);
        keyed.put(ProcessorSpanTimeoutMetricKey.FORCED_SPAN_CLOSES, values.forcedSpanCloses());
        keyed.put(ProcessorSpanTimeoutMetricKey.FORCED_TRACE_CLOSES, values.forcedTraceCloses());
        keyed.put(ProcessorSpanTimeoutMetricKey.TRACKING_EVICTED, values.trackingEvictedTotal());
        keyed.put(ProcessorSpanTimeoutMetricKey.SPANS_REJECTED_PER_TRACE, values.spansRejectedPerTraceTotal());
        keyed.put(ProcessorSpanTimeoutMetricKey.SPANS_REJECTED_GLOBAL, values.spansRejectedGlobalTotal());
        keyed.put(ProcessorSpanTimeoutMetricKey.SPAN_LEDGER_INVARIANT_VIOLATION, values.spanLedgerInvariantViolationTotal());
        return new JmxOwnerState.Present<>(keyed, generation);
    }

    private double activeCount(SpanTimeoutActiveScope scope) {
        return presentSpanTimeoutValues()
                .map(values -> (double) switch (scope) {
                    case SPANS -> values.activeSpanCount();
                    case TRACES -> values.activeTraceCount();
                })
                .orElse(Double.NaN);
    }

    private double trackedTraceCount() {
        return presentSpanTimeoutValues()
                .map(values -> (double) values.trackedTraceCount())
                .orElse(Double.NaN);
    }

    private double capacity(SpanTimeoutCapacityScope scope) {
        return presentSpanTimeoutValues()
                .map(values -> (double) switch (scope) {
                    case TRACES -> values.trackedTraceCapacity();
                    case SPANS -> values.trackedSpanCapacity();
                    case SPANS_PER_TRACE -> values.trackedSpanPerTraceCapacity();
                })
                .orElse(Double.NaN);
    }

    private enum SpanTimeoutCapacityScope {
        TRACES,
        SPANS,
        SPANS_PER_TRACE
    }

    private enum SpanTimeoutActiveScope {
        SPANS,
        TRACES
    }

    private Optional<ProcessorSpanTimeoutValues> presentSpanTimeoutValues() {
        return spanTimeoutObservationSource.get()
                .map(ProcessorMetricsObservation::spanTimeout)
                .flatMap(state -> (state instanceof JmxOwnerState.Present<ProcessorSpanTimeoutValues> present)
                        ? Optional.of(present.values())
                        : Optional.empty());
    }

    static JmxOwnerState<Map<ProcessorCompositeErrorKey, Long>> toCompositeKeyedState(JmxOwnerState<ProcessorCompositeValues> state) {
        if (!(state instanceof JmxOwnerState.Present<ProcessorCompositeValues>(
                ProcessorCompositeValues values, long generation
        ))) {
            return new JmxOwnerState.Absent<>(state.generation());
        }

        Map<String, Long> errorsByName = values.errorsByName();
        Map<ProcessorCompositeErrorKey, Long> keyed = new EnumMap<>(ProcessorCompositeErrorKey.class);
        keyed.put(ProcessorCompositeErrorKey.TOTAL, values.errorsTotal());
        for (ProcessorCompositeErrorKey key : ProcessorCompositeErrorKey.values()) {
            if (key == ProcessorCompositeErrorKey.TOTAL) {
                continue;
            }

            keyed.put(key, errorsByName.getOrDefault(key.processorName(), 0L));
        }

        return new JmxOwnerState.Present<>(keyed, generation);
    }

    static JmxOwnerState<Map<ProcessorMetricsMetricKey, Long>> toMetricsKeyedState(JmxOwnerState<ProcessorMetricsValues> state) {
        if (!(state instanceof JmxOwnerState.Present<ProcessorMetricsValues>(
                ProcessorMetricsValues values, long generation
        ))) {
            return new JmxOwnerState.Absent<>(state.generation());
        }

        Map<ProcessorMetricsMetricKey, Long> keyed = new EnumMap<>(ProcessorMetricsMetricKey.class);
        keyed.put(ProcessorMetricsMetricKey.DROPPED_ATTRIBUTES, values.droppedAttributesTotal());
        keyed.put(ProcessorMetricsMetricKey.DROPPED_EVENTS, values.droppedEventsTotal());
        keyed.put(ProcessorMetricsMetricKey.DROPPED_LINKS, values.droppedLinksTotal());
        return new JmxOwnerState.Present<>(keyed, generation);
    }
}
