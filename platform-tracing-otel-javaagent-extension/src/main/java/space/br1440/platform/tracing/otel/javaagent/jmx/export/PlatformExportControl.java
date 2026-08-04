package space.br1440.platform.tracing.otel.javaagent.jmx.export;

import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxObservationWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxOpenTypeSupport;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationState;
import space.br1440.platform.tracing.otel.javaagent.processor.PlatformDropOldestExportSpanProcessor;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class PlatformExportControl implements PlatformExportControlMBean {

    private static final TabularType SAFE_EXPORTER_METRICS_TYPE = JmxOpenTypeSupport.stringLongMapType(
            "space.br1440.platform.tracing.SafeExporterMetrics", "SafeExporterMetricsEntry"
    );

    private static final JmxOpenTypeSupport.CompositeSchema PROCESSOR_VALUES_SCHEMA = buildProcessorValuesType();
    private static final JmxOpenTypeSupport.CompositeSchema EXPORTER_VALUES_SCHEMA = buildExporterValuesType();
    private static final JmxOpenTypeSupport.CompositeSchema OBSERVATION_TYPE = buildObservationType();

    private final OwnerRegistrationSlot<PlatformDropOldestExportSpanProcessor> processorSlot;
    private final OwnerRegistrationSlot<SafeSpanExporter> exporterSlot;
    private final UUID agentInstanceId;
    private final AtomicLong captureSequence = new AtomicLong();

    public PlatformExportControl(OwnerRegistrationSlot<PlatformDropOldestExportSpanProcessor> processorSlot,
                                 OwnerRegistrationSlot<SafeSpanExporter> exporterSlot,
                                 UUID agentInstanceId) {
        this.processorSlot = processorSlot;
        this.exporterSlot = exporterSlot;
        this.agentInstanceId = agentInstanceId;
    }

    @Override
    public CompositeData getExportObservation() {
        long sequence = captureSequence.incrementAndGet();
        long captureStartedAtMillis = Instant.now().toEpochMilli();
        long baseNanos = System.nanoTime();

        CompositeData processorValues = buildProcessorValues(processorSlot.capture(), baseNanos);
        CompositeData exporterValues = buildExporterValues(exporterSlot.capture(), baseNanos);

        long captureCompletedAtMillis = Instant.now().toEpochMilli();
        return JmxOpenTypeSupport.compositeData(OBSERVATION_TYPE,
                JmxObservationWireContract.SCHEMA_VERSION,
                sequence,
                agentInstanceId.toString(),
                captureStartedAtMillis,
                captureCompletedAtMillis,
                processorValues,
                exporterValues);
    }

    private static CompositeData buildProcessorValues(OwnerRegistrationState<PlatformDropOldestExportSpanProcessor> state,
                                                      long baseNanos) {
        long offsetNanos = (System.nanoTime() - baseNanos);

        if (state instanceof OwnerRegistrationState.Present<PlatformDropOldestExportSpanProcessor>(
                PlatformDropOldestExportSpanProcessor owner, long generation
        )) {
            return JmxOpenTypeSupport.compositeData(PROCESSOR_VALUES_SCHEMA,
                    true, generation, offsetNanos,
                    owner.getQueueCapacity(), owner.getQueueSize(),
                    owner.getDroppedSpansOverflow(), owner.getDroppedSpansAfterShutdown(),
                    owner.getExportFailures(), owner.getExportTimeouts());
        }

        return JmxOpenTypeSupport.compositeData(
                PROCESSOR_VALUES_SCHEMA,
                false, state.generation(), offsetNanos, 0, 0, 0L, 0L, 0L, 0L
        );
    }

    private static CompositeData buildExporterValues(OwnerRegistrationState<SafeSpanExporter> state, long baseNanos) {
        long offsetNanos = System.nanoTime() - baseNanos;

        if (state instanceof OwnerRegistrationState.Present<SafeSpanExporter>(SafeSpanExporter owner, long generation)) {
            Map<String, Long> metrics = owner.metricsSnapshot();
            return JmxOpenTypeSupport.compositeData(EXPORTER_VALUES_SCHEMA,
                    true, generation, offsetNanos,
                    owner.isExportEnabled(),
                    owner.getLastExportDurationNanos(),
                    JmxOpenTypeSupport.stringLongMap(SAFE_EXPORTER_METRICS_TYPE, metrics));
        }

        return JmxOpenTypeSupport.compositeData(
                EXPORTER_VALUES_SCHEMA,
                false, state.generation(), offsetNanos, false, 0L,
                JmxOpenTypeSupport.stringLongMap(SAFE_EXPORTER_METRICS_TYPE, Map.of())
        );
    }

    private static JmxOpenTypeSupport.CompositeSchema buildProcessorValuesType() {
        LinkedHashMap<String, OpenType<?>> items = new LinkedHashMap<>();
        items.put(JmxObservationWireContract.ITEM_OWNER_PRESENT, SimpleType.BOOLEAN);
        items.put(JmxObservationWireContract.ITEM_OWNER_GENERATION, SimpleType.LONG);
        items.put(JmxObservationWireContract.ITEM_OWNER_CAPTURED_OFFSET_NANOS, SimpleType.LONG);
        items.put(JmxObservationWireContract.EXPORT_PROCESSOR_ITEM_QUEUE_CAPACITY, SimpleType.INTEGER);
        items.put(JmxObservationWireContract.EXPORT_PROCESSOR_ITEM_QUEUE_SIZE, SimpleType.INTEGER);
        items.put(JmxObservationWireContract.EXPORT_PROCESSOR_ITEM_DROPPED_OVERFLOW_TOTAL, SimpleType.LONG);
        items.put(JmxObservationWireContract.EXPORT_PROCESSOR_ITEM_DROPPED_AFTER_SHUTDOWN_TOTAL, SimpleType.LONG);
        items.put(JmxObservationWireContract.EXPORT_PROCESSOR_ITEM_FAILURES_TOTAL, SimpleType.LONG);
        items.put(JmxObservationWireContract.EXPORT_PROCESSOR_ITEM_TIMEOUTS_TOTAL, SimpleType.LONG);
        return JmxOpenTypeSupport.compositeType(JmxObservationWireContract.EXPORT_PROCESSOR_VALUES_TYPE_NAME, items);
    }

    private static JmxOpenTypeSupport.CompositeSchema buildExporterValuesType() {
        LinkedHashMap<String, OpenType<?>> items = new LinkedHashMap<>();
        items.put(JmxObservationWireContract.ITEM_OWNER_PRESENT, SimpleType.BOOLEAN);
        items.put(JmxObservationWireContract.ITEM_OWNER_GENERATION, SimpleType.LONG);
        items.put(JmxObservationWireContract.ITEM_OWNER_CAPTURED_OFFSET_NANOS, SimpleType.LONG);
        items.put(JmxObservationWireContract.EXPORT_EXPORTER_ITEM_ENABLED, SimpleType.BOOLEAN);
        items.put(JmxObservationWireContract.EXPORT_EXPORTER_ITEM_LAST_DURATION_NANOS, SimpleType.LONG);
        items.put(JmxObservationWireContract.EXPORT_EXPORTER_ITEM_METRICS, SAFE_EXPORTER_METRICS_TYPE);
        return JmxOpenTypeSupport.compositeType(JmxObservationWireContract.EXPORT_EXPORTER_VALUES_TYPE_NAME, items);
    }

    private static JmxOpenTypeSupport.CompositeSchema buildObservationType() {
        LinkedHashMap<String, OpenType<?>> items = JmxOpenTypeSupport.observationFrameItems();
        items.put(JmxObservationWireContract.EXPORT_ITEM_PROCESSOR, PROCESSOR_VALUES_SCHEMA.type());
        items.put(JmxObservationWireContract.EXPORT_ITEM_EXPORTER, EXPORTER_VALUES_SCHEMA.type());
        return JmxOpenTypeSupport.compositeType(JmxObservationWireContract.EXPORT_COMPOSITE_TYPE_NAME, items);
    }
}
