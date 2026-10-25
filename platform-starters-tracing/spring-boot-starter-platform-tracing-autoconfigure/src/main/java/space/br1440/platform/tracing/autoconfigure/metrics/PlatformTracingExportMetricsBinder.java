package space.br1440.platform.tracing.autoconfigure.metrics;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export.ExportObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export.ExportProcessorValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export.SafeExporterValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

final class PlatformTracingExportMetricsBinder implements MeterBinder {

    private static final String PROCESSOR_METRIC_NAME = "platform.tracing.export.processor.count";
    private static final String EXPORTER_METRIC_NAME = "platform.tracing.export.exporter.count";
    private static final String LIFECYCLE_METRIC_NAME = "platform.tracing.export.processor.lifecycle";
    private static final String DOMAIN = "export";
    private static final String PROCESSOR_OWNER = "processor";
    private static final String EXPORTER_OWNER = "exporter";

    private final CoalescingJmxObservationSource<ExportObservation> observationSource;
    private final MonotonicJmxCounterGroupBridge<ExportProcessorMetricKey> processorBridge;
    private final MonotonicJmxCounterGroupBridge<ExportExporterMetricKey> exporterBridge;

    PlatformTracingExportMetricsBinder(PlatformTracingJmxClient client) {
        this(client, System::nanoTime);
    }

    PlatformTracingExportMetricsBinder(PlatformTracingJmxClient client, MonotonicJmxCounterGroupBridge.Ticker ticker) {
        this.observationSource = new CoalescingJmxObservationSource<>(
                () -> client.export().observation(),
                ticker
        );
        this.processorBridge = new MonotonicJmxCounterGroupBridge<>(
                ExportProcessorMetricKey.class, this::processorSnapshot, ticker,
                MonotonicJmxCounterGroupBridge.DEFAULT_COALESCING_WINDOW_NANOS);
        this.exporterBridge = new MonotonicJmxCounterGroupBridge<>(
                ExportExporterMetricKey.class, this::exporterSnapshot, ticker,
                MonotonicJmxCounterGroupBridge.DEFAULT_COALESCING_WINDOW_NANOS);
    }

    @Override
    public void bindTo(@Nonnull MeterRegistry registry) {
        for (ExportProcessorMetricKey key : ExportProcessorMetricKey.values()) {
            FunctionCounter.builder(PROCESSOR_METRIC_NAME, processorBridge, bridge -> bridge.count(key))
                    .tags("metric", key.metricTag())
                    .description("Cumulative counters of the export span processor owner")
                    .register(registry);

            FunctionCounter.builder(MonotonicJmxCounterGroupBridge.ANOMALY_METRIC_NAME, processorBridge, bridge -> bridge.anomalyCount(key))
                    .tags("domain", DOMAIN, "owner", PROCESSOR_OWNER, "metric", key.metricTag())
                    .description("Backward-moving cumulative JMX values observed for a single owner generation")
                    .register(registry);
        }
        bindResets(registry, processorBridge, PROCESSOR_OWNER);

        for (ExportProcessorValues.LifecycleState state : ExportProcessorValues.LifecycleState.values()) {
            Gauge.builder(LIFECYCLE_METRIC_NAME, this, binder -> binder.lifecycleOneHot(state))
                    .tags("state", state.name().toLowerCase(Locale.ROOT))
                    .description("Bounded one-hot gauge of the export processor lifecycle state (0|1|NaN)")
                    .register(registry);
        }

        for (ExportExporterMetricKey key : ExportExporterMetricKey.values()) {
            FunctionCounter.builder(EXPORTER_METRIC_NAME, exporterBridge, bridge -> bridge.count(key))
                    .tags("metric", key.wireKey())
                    .description("Cumulative counters of the safe span exporter owner")
                    .register(registry);

            FunctionCounter.builder(MonotonicJmxCounterGroupBridge.ANOMALY_METRIC_NAME, exporterBridge, bridge -> bridge.anomalyCount(key))
                    .tags("domain", DOMAIN, "owner", EXPORTER_OWNER, "metric", key.wireKey())
                    .description("Backward-moving cumulative JMX values observed for a single owner generation")
                    .register(registry);
        }
        bindResets(registry, exporterBridge, EXPORTER_OWNER);
    }

    private static <K extends Enum<K>> void bindResets(MeterRegistry registry, MonotonicJmxCounterGroupBridge<K> bridge, String owner) {
        for (MonotonicJmxCounterGroupBridge.ResetCause cause : MonotonicJmxCounterGroupBridge.ResetCause.values()) {
            FunctionCounter.builder(MonotonicJmxCounterGroupBridge.RESET_METRIC_NAME, bridge, b -> b.resetCount(cause))
                    .tags("domain", DOMAIN, "owner", owner, "cause", cause.tag())
                    .description("Source identity resets observed for a cumulative JMX counter group")
                    .register(registry);
        }
    }

    private Optional<MonotonicJmxCounterGroupBridge.SourceSnapshot<ExportProcessorMetricKey>> processorSnapshot() {
        return observationSource.get()
                .map(observation -> new MonotonicJmxCounterGroupBridge.SourceSnapshot<>(
                        toProcessorKeyedState(observation.processor()),
                        observation.agentInstanceId()
                ));
    }

    private Optional<MonotonicJmxCounterGroupBridge.SourceSnapshot<ExportExporterMetricKey>> exporterSnapshot() {
        return observationSource.get()
                .map(observation -> new MonotonicJmxCounterGroupBridge.SourceSnapshot<>(
                        toExporterKeyedState(observation.exporter()),
                        observation.agentInstanceId()
                ));
    }

    private double lifecycleOneHot(ExportProcessorValues.LifecycleState state) {
        return observationSource.get()
                .map(ExportObservation::processor)
                .flatMap(PlatformTracingExportMetricsBinder::presentProcessorValues)
                .map(ExportProcessorValues::lifecycleState)
                .map(current -> state.equals(current) ? 1.0 : 0.0)
                .orElse(Double.NaN);
    }

    private static Optional<ExportProcessorValues> presentProcessorValues(JmxOwnerState<ExportProcessorValues> state) {
        if (state instanceof JmxOwnerState.Present<ExportProcessorValues> present) {
            return Optional.of(present.values());
        }
        return Optional.empty();
    }

    static JmxOwnerState<Map<ExportProcessorMetricKey, Long>> toProcessorKeyedState(JmxOwnerState<ExportProcessorValues> state) {
        if (!(state instanceof JmxOwnerState.Present<ExportProcessorValues>(
                ExportProcessorValues values, long generation
        ))) {
            return new JmxOwnerState.Absent<>(state.generation());
        }

        Map<ExportProcessorMetricKey, Long> keyed = new EnumMap<>(ExportProcessorMetricKey.class);
        keyed.put(ExportProcessorMetricKey.DROPPED_OVERFLOW, values.droppedOverflowTotal());
        keyed.put(ExportProcessorMetricKey.DROPPED_AFTER_SHUTDOWN, values.droppedAfterShutdownTotal());
        keyed.put(ExportProcessorMetricKey.DROPPED_AFTER_FAILURE, values.droppedAfterFailureTotal());
        keyed.put(ExportProcessorMetricKey.DISCARDED_QUEUED, values.discardedQueuedTotal());
        keyed.put(ExportProcessorMetricKey.FAILURES, values.failuresTotal());
        keyed.put(ExportProcessorMetricKey.SHUTDOWN_FAILURES, values.shutdownFailuresTotal());
        keyed.put(ExportProcessorMetricKey.TIMEOUTS, values.timeoutsTotal());
        return new JmxOwnerState.Present<>(keyed, generation);
    }

    static JmxOwnerState<Map<ExportExporterMetricKey, Long>> toExporterKeyedState(JmxOwnerState<SafeExporterValues> state) {
        if (!(state instanceof JmxOwnerState.Present<SafeExporterValues>(SafeExporterValues values, long generation))) {
            return new JmxOwnerState.Absent<>(state.generation());
        }

        Map<String, Long> wireCounts = values.metrics();
        Map<ExportExporterMetricKey, Long> keyed = new EnumMap<>(ExportExporterMetricKey.class);
        for (ExportExporterMetricKey key : ExportExporterMetricKey.values()) {
            keyed.put(key, wireCounts.getOrDefault(key.wireKey(), 0L));
        }

        return new JmxOwnerState.Present<>(keyed, generation);
    }
}
