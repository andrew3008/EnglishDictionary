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
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorWatchdogValues;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

final class PlatformTracingProcessorMetricsBinder implements MeterBinder {

    private static final String WATCHDOG_METRIC_NAME = "platform.tracing.processor.watchdog.count";
    private static final String COMPOSITE_ERRORS_TOTAL_METRIC_NAME = "platform.tracing.processor.composite.errors_total";
    private static final String COMPOSITE_ERRORS_BY_NAME_METRIC_NAME = "platform.tracing.processor.composite.errors_by_name";
    private static final String METRICS_METRIC_NAME = "platform.tracing.processor.metrics.count";
    private static final String DOMAIN = "processor_metrics";
    private static final String WATCHDOG_OWNER = "watchdog";
    private static final String COMPOSITE_OWNER = "composite";
    private static final String METRICS_OWNER = "metrics";

    private final PlatformTracingJmxClient client;
    private final MonotonicJmxCounterGroupBridge<ProcessorWatchdogMetricKey> watchdogBridge;
    private final MonotonicJmxCounterGroupBridge<ProcessorCompositeErrorKey> compositeBridge;
    private final MonotonicJmxCounterGroupBridge<ProcessorMetricsMetricKey> metricsBridge;

    PlatformTracingProcessorMetricsBinder(PlatformTracingJmxClient client) {
        this(client, System::nanoTime);
    }

    PlatformTracingProcessorMetricsBinder(PlatformTracingJmxClient client, MonotonicJmxCounterGroupBridge.Ticker ticker) {
        this.client = client;

        this.watchdogBridge = new MonotonicJmxCounterGroupBridge<>(
                ProcessorWatchdogMetricKey.class, this::watchdogCounts, this::processorMetricsAgentInstanceId, ticker,
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
        for (ProcessorWatchdogMetricKey key : ProcessorWatchdogMetricKey.values()) {
            FunctionCounter.builder(WATCHDOG_METRIC_NAME, watchdogBridge, b -> b.count(key))
                    .tags("metric", key.metricTag())
                    .description("Cumulative counters of the span watchdog processor owner")
                    .register(registry);

            FunctionCounter.builder(MonotonicJmxCounterGroupBridge.ANOMALY_METRIC_NAME, watchdogBridge, b -> b.anomalyCount(key))
                    .tags("domain", DOMAIN, "owner", WATCHDOG_OWNER, "metric", key.metricTag())
                    .description("Backward-moving cumulative JMX values observed for a single owner generation")
                    .register(registry);
        }
        bindResets(registry, watchdogBridge, WATCHDOG_OWNER);

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

    private Optional<JmxOwnerState<Map<ProcessorWatchdogMetricKey, Long>>> watchdogCounts() {
        return client.processorMetrics().observation()
                .map(ProcessorMetricsObservation::watchdog)
                .map(PlatformTracingProcessorMetricsBinder::toWatchdogKeyedState);
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
                .map(ProcessorMetricsObservation::agentInstanceId)
                .orElseGet(MonotonicJmxCounterGroupBridge.LOCAL_PROCESS_IDENTITY);
    }

    static JmxOwnerState<Map<ProcessorWatchdogMetricKey, Long>> toWatchdogKeyedState(JmxOwnerState<ProcessorWatchdogValues> state) {
        if (!(state instanceof JmxOwnerState.Present<ProcessorWatchdogValues>(
                ProcessorWatchdogValues values, long generation
        ))) {
            return new JmxOwnerState.Absent<>(state.generation());
        }

        Map<ProcessorWatchdogMetricKey, Long> keyed = new EnumMap<>(ProcessorWatchdogMetricKey.class);
        keyed.put(ProcessorWatchdogMetricKey.FORCED_SPAN_CLOSES, values.forcedSpanCloses());
        keyed.put(ProcessorWatchdogMetricKey.FORCED_TRACE_CLOSES, values.forcedTraceCloses());
        return new JmxOwnerState.Present<>(keyed, generation);
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
