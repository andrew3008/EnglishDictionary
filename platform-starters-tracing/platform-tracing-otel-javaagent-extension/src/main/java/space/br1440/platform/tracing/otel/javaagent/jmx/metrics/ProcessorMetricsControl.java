package space.br1440.platform.tracing.otel.javaagent.jmx.metrics;

import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxObservationWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxOpenTypeSupport;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerCapture;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationState;
import space.br1440.platform.tracing.otel.javaagent.processor.SpanLimitsMetricsProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.SpanWatchdogProcessor;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class ProcessorMetricsControl implements ProcessorMetricsControlMBean {

    private static final TabularType ERRORS_BY_NAME_TYPE = JmxOpenTypeSupport.stringLongMapType(
            "space.br1440.platform.tracing.ProcessorErrorsByName", "ProcessorErrorsByNameEntry"
    );

    private static final JmxOpenTypeSupport.CompositeSchema WATCHDOG_VALUES_SCHEMA = buildWatchdogValuesType();
    private static final JmxOpenTypeSupport.CompositeSchema COMPOSITE_VALUES_SCHEMA = buildCompositeValuesType();
    private static final JmxOpenTypeSupport.CompositeSchema METRICS_VALUES_SCHEMA = buildMetricsValuesType();
    private static final JmxOpenTypeSupport.CompositeSchema OBSERVATION_TYPE = buildObservationType();

    private final OwnerRegistrationSlot<SpanWatchdogProcessor> watchdogSlot;
    private final OwnerRegistrationSlot<PlatformCompositeSpanProcessor> compositeSlot;
    private final OwnerRegistrationSlot<SpanLimitsMetricsProcessor> metricsSlot;
    private final UUID agentInstanceId;
    private final AtomicLong captureSequence = new AtomicLong();

    public ProcessorMetricsControl(OwnerRegistrationSlot<SpanWatchdogProcessor> watchdogSlot,
                                           OwnerRegistrationSlot<PlatformCompositeSpanProcessor> compositeSlot,
                                           OwnerRegistrationSlot<SpanLimitsMetricsProcessor> metricsSlot,
                                           UUID agentInstanceId) {
        this.watchdogSlot = Objects.requireNonNull(watchdogSlot, "watchdogSlot");
        this.compositeSlot = Objects.requireNonNull(compositeSlot, "compositeSlot");
        this.metricsSlot = Objects.requireNonNull(metricsSlot, "metricsSlot");
        this.agentInstanceId = Objects.requireNonNull(agentInstanceId, "agentInstanceId");
    }

    @Override
    public CompositeData getProcessorMetricsObservation() {
        long sequence = captureSequence.incrementAndGet();
        long startedAt = Instant.now().toEpochMilli();

        OwnerCapture<SpanWatchdogProcessor> watchdogCapture = OwnerCapture.of(watchdogSlot);
        OwnerCapture<PlatformCompositeSpanProcessor> compositeCapture = OwnerCapture.of(compositeSlot);
        OwnerCapture<SpanLimitsMetricsProcessor> metricsCapture = OwnerCapture.of(metricsSlot);

        CompositeData watchdogValues = buildWatchdogValues(watchdogCapture.state(), watchdogCapture.offsetNanos());
        CompositeData compositeValues = buildCompositeValues(compositeCapture.state(), compositeCapture.offsetNanos());
        CompositeData metricsValues = buildMetricsValues(metricsCapture.state(), metricsCapture.offsetNanos());

        long completedAt = Instant.now().toEpochMilli();
        return JmxOpenTypeSupport.compositeData(
                OBSERVATION_TYPE,
                JmxObservationWireContract.SCHEMA_VERSION,
                sequence,
                agentInstanceId.toString(),
                startedAt,
                completedAt,
                watchdogValues,
                compositeValues,
                metricsValues
        );
    }

    private static CompositeData buildWatchdogValues(OwnerRegistrationState<SpanWatchdogProcessor> state, long offsetNanos) {
        if (state instanceof OwnerRegistrationState.Present<SpanWatchdogProcessor>(
                SpanWatchdogProcessor owner, long generation
        )) {
            return JmxOpenTypeSupport.compositeData(
                    WATCHDOG_VALUES_SCHEMA,
                    true, generation, offsetNanos,
                    owner.getForcedSpanCloses(), owner.getForcedTraceCloses(),
                    owner.getActiveSpanCount(), owner.getActiveTraceCount()
            );
        }

        return JmxOpenTypeSupport.compositeData(
                WATCHDOG_VALUES_SCHEMA,
                false, state.generation(), offsetNanos, 0L, 0L, 0, 0
        );
    }

    private static CompositeData buildCompositeValues(OwnerRegistrationState<PlatformCompositeSpanProcessor> state, long offsetNanos) {
        if (state instanceof OwnerRegistrationState.Present<PlatformCompositeSpanProcessor>(
                PlatformCompositeSpanProcessor owner, long generation
        )) {
            Map<String, Long> errorsByName = owner.getProcessorErrorCounts();
            long total = 0L;
            for (Long value : errorsByName.values()) {
                if (value != null) {
                    total += value;
                }
            }

            return JmxOpenTypeSupport.compositeData(
                    COMPOSITE_VALUES_SCHEMA,
                    true, generation, offsetNanos,
                    total, JmxOpenTypeSupport.stringLongMap(ERRORS_BY_NAME_TYPE, errorsByName)
            );
        }

        return JmxOpenTypeSupport.compositeData(
                COMPOSITE_VALUES_SCHEMA,
                false, state.generation(), offsetNanos, 0L,
                JmxOpenTypeSupport.stringLongMap(ERRORS_BY_NAME_TYPE, Map.of())
        );
    }

    private static CompositeData buildMetricsValues(OwnerRegistrationState<SpanLimitsMetricsProcessor> state, long offsetNanos) {
        if (state instanceof OwnerRegistrationState.Present<SpanLimitsMetricsProcessor>(
                SpanLimitsMetricsProcessor owner, long generation
        )) {
            return JmxOpenTypeSupport.compositeData(
                    METRICS_VALUES_SCHEMA,
                    true, generation, offsetNanos,
                    owner.getDroppedAttributes(), owner.getDroppedEvents(), owner.getDroppedLinks()
            );
        }

        return JmxOpenTypeSupport.compositeData(
                METRICS_VALUES_SCHEMA,
                false, state.generation(), offsetNanos, 0L, 0L, 0L
        );
    }

    private static JmxOpenTypeSupport.CompositeSchema buildWatchdogValuesType() {
        LinkedHashMap<String, OpenType<?>> items = new LinkedHashMap<>();
        items.put(JmxObservationWireContract.ITEM_OWNER_PRESENT, SimpleType.BOOLEAN);
        items.put(JmxObservationWireContract.ITEM_OWNER_GENERATION, SimpleType.LONG);
        items.put(JmxObservationWireContract.ITEM_OWNER_CAPTURED_OFFSET_NANOS, SimpleType.LONG);
        items.put(JmxObservationWireContract.PROCESSOR_METRICS_WATCHDOG_ITEM_FORCED_SPAN_CLOSES, SimpleType.LONG);
        items.put(JmxObservationWireContract.PROCESSOR_METRICS_WATCHDOG_ITEM_FORCED_TRACE_CLOSES, SimpleType.LONG);
        items.put(JmxObservationWireContract.PROCESSOR_METRICS_WATCHDOG_ITEM_ACTIVE_SPAN_COUNT, SimpleType.INTEGER);
        items.put(JmxObservationWireContract.PROCESSOR_METRICS_WATCHDOG_ITEM_ACTIVE_TRACE_COUNT, SimpleType.INTEGER);
        return JmxOpenTypeSupport.compositeType(JmxObservationWireContract.PROCESSOR_METRICS_WATCHDOG_VALUES_TYPE_NAME, items);
    }

    private static JmxOpenTypeSupport.CompositeSchema buildCompositeValuesType() {
        LinkedHashMap<String, OpenType<?>> items = new LinkedHashMap<>();
        items.put(JmxObservationWireContract.ITEM_OWNER_PRESENT, SimpleType.BOOLEAN);
        items.put(JmxObservationWireContract.ITEM_OWNER_GENERATION, SimpleType.LONG);
        items.put(JmxObservationWireContract.ITEM_OWNER_CAPTURED_OFFSET_NANOS, SimpleType.LONG);
        items.put(JmxObservationWireContract.PROCESSOR_METRICS_COMPOSITE_ITEM_ERRORS_TOTAL, SimpleType.LONG);
        items.put(JmxObservationWireContract.PROCESSOR_METRICS_COMPOSITE_ITEM_ERRORS_BY_NAME, ERRORS_BY_NAME_TYPE);
        return JmxOpenTypeSupport.compositeType(JmxObservationWireContract.PROCESSOR_METRICS_COMPOSITE_VALUES_TYPE_NAME, items);
    }

    private static JmxOpenTypeSupport.CompositeSchema buildMetricsValuesType() {
        LinkedHashMap<String, OpenType<?>> items = new LinkedHashMap<>();
        items.put(JmxObservationWireContract.ITEM_OWNER_PRESENT, SimpleType.BOOLEAN);
        items.put(JmxObservationWireContract.ITEM_OWNER_GENERATION, SimpleType.LONG);
        items.put(JmxObservationWireContract.ITEM_OWNER_CAPTURED_OFFSET_NANOS, SimpleType.LONG);
        items.put(JmxObservationWireContract.PROCESSOR_METRICS_METRICS_ITEM_DROPPED_ATTRIBUTES_TOTAL, SimpleType.LONG);
        items.put(JmxObservationWireContract.PROCESSOR_METRICS_METRICS_ITEM_DROPPED_EVENTS_TOTAL, SimpleType.LONG);
        items.put(JmxObservationWireContract.PROCESSOR_METRICS_METRICS_ITEM_DROPPED_LINKS_TOTAL, SimpleType.LONG);
        return JmxOpenTypeSupport.compositeType(JmxObservationWireContract.PROCESSOR_METRICS_METRICS_VALUES_TYPE_NAME, items);
    }

    private static JmxOpenTypeSupport.CompositeSchema buildObservationType() {
        LinkedHashMap<String, OpenType<?>> items = JmxOpenTypeSupport.observationFrameItems();
        items.put(JmxObservationWireContract.PROCESSOR_METRICS_ITEM_WATCHDOG, WATCHDOG_VALUES_SCHEMA.type());
        items.put(JmxObservationWireContract.PROCESSOR_METRICS_ITEM_COMPOSITE, COMPOSITE_VALUES_SCHEMA.type());
        items.put(JmxObservationWireContract.PROCESSOR_METRICS_ITEM_METRICS, METRICS_VALUES_SCHEMA.type());
        return JmxOpenTypeSupport.compositeType(JmxObservationWireContract.PROCESSOR_METRICS_COMPOSITE_TYPE_NAME, items);
    }
}
