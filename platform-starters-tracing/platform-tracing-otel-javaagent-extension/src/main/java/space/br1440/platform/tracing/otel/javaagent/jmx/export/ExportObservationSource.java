package space.br1440.platform.tracing.otel.javaagent.jmx.export;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxOpenTypeSupport;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationCaptureEnvelope;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationSnapshotStore;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerCapture;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationState;
import space.br1440.platform.tracing.otel.javaagent.processor.dropoldest.PlatformDropOldestExportSpanProcessor;

public final class ExportObservationSource {

    private static final String ABSENT_LIFECYCLE_STATE_PLACEHOLDER = "ABSENT";

    private final OwnerRegistrationSlot<PlatformDropOldestExportSpanProcessor> processorSlot;
    private final OwnerRegistrationSlot<SafeSpanExporter> exporterSlot;
    private final ObservationCaptureEnvelope envelope;
    private final ObservationSnapshotStore snapshotStore = new ObservationSnapshotStore(JmxOpenTypes.EXPORT_OBSERVATION);

    public ExportObservationSource(OwnerRegistrationSlot<PlatformDropOldestExportSpanProcessor> processorSlot,
                                   OwnerRegistrationSlot<SafeSpanExporter> exporterSlot,
                                   AgentInstanceId agentInstanceId) {
        this.processorSlot = Objects.requireNonNull(processorSlot, "processorSlot");
        this.exporterSlot = Objects.requireNonNull(exporterSlot, "exporterSlot");
        this.envelope = new ObservationCaptureEnvelope(agentInstanceId);
        publishObservationSnapshot();
    }

    public CompositeData getExportObservation() {
        return snapshotStore.current();
    }

    public synchronized void publishObservationSnapshot() {
        ObservationCaptureEnvelope.OpenCapture openCapture = envelope.open();

        OwnerCapture<PlatformDropOldestExportSpanProcessor> processorCapture = OwnerCapture.of(processorSlot);
        OwnerCapture<SafeSpanExporter> exporterCapture = OwnerCapture.of(exporterSlot);

        CompositeData processorValues = buildProcessorValues(processorCapture.state(), processorCapture.offsetNanos());
        CompositeData exporterValues = buildExporterValues(exporterCapture.state(), exporterCapture.offsetNanos());

        Map<String, Object> values = new LinkedHashMap<>();
        openCapture.sealInto(values);
        values.put(JmxWireContract.FIELD_EXPORT_OBSERVATION_PROCESSOR, processorValues);
        values.put(JmxWireContract.FIELD_EXPORT_OBSERVATION_EXPORTER, exporterValues);
        snapshotStore.publish(values);
    }

    private static CompositeData buildProcessorValues(OwnerRegistrationState<PlatformDropOldestExportSpanProcessor> state, long offsetNanos) {
        if (state instanceof OwnerRegistrationState.Present<PlatformDropOldestExportSpanProcessor>(
                PlatformDropOldestExportSpanProcessor owner, long generation
        )) {
            PlatformDropOldestExportSpanProcessor.MetricsSnapshot snapshot = owner.metricsSnapshot();
            return processorValues(
                    true,
                    generation,
                    offsetNanos,
                    snapshot.queueCapacity(),
                    snapshot.queueSize(),
                    snapshot.droppedOverflowTotal(),
                    snapshot.droppedAfterShutdownTotal(),
                    snapshot.failuresTotal(),
                    snapshot.timeoutsTotal(),
                    snapshot.lifecycleState(),
                    snapshot.shutdownFailuresTotal(),
                    snapshot.droppedAfterFailureTotal(),
                    snapshot.discardedQueuedTotal()
            );
        }

        return processorValues(false, state.generation(), offsetNanos, 0, 0, 0L, 0L, 0L, 0L,
                ABSENT_LIFECYCLE_STATE_PLACEHOLDER, 0L, 0L, 0L);
    }

    private static CompositeData buildExporterValues(OwnerRegistrationState<SafeSpanExporter> state, long offsetNanos) {
        if (state instanceof OwnerRegistrationState.Present<SafeSpanExporter>(
                SafeSpanExporter owner, long generation
        )) {
            Map<String, Long> metrics = owner.metricsSnapshot();
            return exporterValues(
                    true, generation, offsetNanos, owner.isExportEnabled(),
                    owner.getLastExportDurationNanos(), metrics
            );
        }

        return exporterValues(false, state.generation(), offsetNanos, false, 0L, Map.of());
    }

    private static CompositeData processorValues(boolean present,
                                                 long generation,
                                                 long offsetNanos,
                                                 int queueCapacity,
                                                 int queueSize,
                                                 long droppedOverflowTotal,
                                                 long droppedAfterShutdownTotal,
                                                 long failuresTotal,
                                                 long timeoutsTotal,
                                                 String lifecycleState,
                                                 long shutdownFailuresTotal,
                                                 long droppedAfterFailureTotal,
                                                 long discardedQueuedTotal) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_OWNER_PRESENT, present);
        values.put(JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_OWNER_GENERATION, generation);
        values.put(JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_OWNER_CAPTURED_OFFSET_NANOS, offsetNanos);
        values.put(JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_QUEUE_CAPACITY, queueCapacity);
        values.put(JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_QUEUE_SIZE, queueSize);
        values.put(JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_DROPPED_OVERFLOW_TOTAL,
                droppedOverflowTotal);
        values.put(JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_DROPPED_AFTER_SHUTDOWN_TOTAL,
                droppedAfterShutdownTotal);
        values.put(JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_FAILURES_TOTAL, failuresTotal);
        values.put(JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_TIMEOUTS_TOTAL, timeoutsTotal);
        values.put(JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_LIFECYCLE_STATE, lifecycleState);
        values.put(JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_SHUTDOWN_FAILURES_TOTAL,
                shutdownFailuresTotal);
        values.put(JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_DROPPED_AFTER_FAILURE_TOTAL,
                droppedAfterFailureTotal);
        values.put(JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_DISCARDED_QUEUED_TOTAL,
                discardedQueuedTotal);
        return JmxOpenTypeSupport.compositeData(JmxOpenTypes.EXPORT_PROCESSOR_VALUES, values);
    }

    private static CompositeData exporterValues(boolean present,
                                                long generation,
                                                long offsetNanos,
                                                boolean exportEnabled,
                                                long lastExportDurationNanos,
                                                Map<String, Long> metrics) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(JmxWireContract.FIELD_SAFE_EXPORTER_VALUES_OWNER_PRESENT, present);
        values.put(JmxWireContract.FIELD_SAFE_EXPORTER_VALUES_OWNER_GENERATION, generation);
        values.put(JmxWireContract.FIELD_SAFE_EXPORTER_VALUES_OWNER_CAPTURED_OFFSET_NANOS, offsetNanos);
        values.put(JmxWireContract.FIELD_SAFE_EXPORTER_VALUES_EXPORT_ENABLED, exportEnabled);
        values.put(JmxWireContract.FIELD_SAFE_EXPORTER_VALUES_LAST_EXPORT_DURATION_NANOS, lastExportDurationNanos);
        values.put(JmxWireContract.FIELD_SAFE_EXPORTER_VALUES_SAFE_EXPORTER_METRICS,
                JmxOpenTypeSupport.stringLongMap(
                        JmxOpenTypes.SAFE_EXPORTER_METRICS,
                        JmxWireContract.FIELD_SAFE_EXPORTER_METRICS_KEY,
                        JmxWireContract.FIELD_SAFE_EXPORTER_METRICS_VALUE,
                        metrics
                )
        );

        return JmxOpenTypeSupport.compositeData(JmxOpenTypes.SAFE_EXPORTER_VALUES, values);
    }
}
