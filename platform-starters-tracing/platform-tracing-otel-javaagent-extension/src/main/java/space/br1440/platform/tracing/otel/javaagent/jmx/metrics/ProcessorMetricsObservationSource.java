package space.br1440.platform.tracing.otel.javaagent.jmx.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxOpenTypeSupport;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationCaptureEnvelope;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationSnapshotStore;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerCapture;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationState;
import space.br1440.platform.tracing.otel.javaagent.processor.SpanLimitsMetricsProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.watchdog.SpanWatchdogProcessor;

public final class ProcessorMetricsObservationSource {

    private final OwnerRegistrationSlot<SpanWatchdogProcessor> watchdogSlot;
    private final OwnerRegistrationSlot<PlatformCompositeSpanProcessor> compositeSlot;
    private final OwnerRegistrationSlot<SpanLimitsMetricsProcessor> metricsSlot;
    private final ObservationCaptureEnvelope envelope;
    private final ObservationSnapshotStore snapshotStore =
            new ObservationSnapshotStore(JmxOpenTypes.PROCESSOR_METRICS_OBSERVATION);

    public ProcessorMetricsObservationSource(OwnerRegistrationSlot<SpanWatchdogProcessor> watchdogSlot,
                                   OwnerRegistrationSlot<PlatformCompositeSpanProcessor> compositeSlot,
                                   OwnerRegistrationSlot<SpanLimitsMetricsProcessor> metricsSlot,
                                   UUID agentInstanceId) {
        this.watchdogSlot = Objects.requireNonNull(watchdogSlot, "watchdogSlot");
        this.compositeSlot = Objects.requireNonNull(compositeSlot, "compositeSlot");
        this.metricsSlot = Objects.requireNonNull(metricsSlot, "metricsSlot");
        this.envelope = new ObservationCaptureEnvelope(agentInstanceId);
        publishObservationSnapshot();
    }

    public CompositeData getProcessorMetricsObservation() {
        return snapshotStore.current();
    }

    public synchronized void publishObservationSnapshot() {
        ObservationCaptureEnvelope.OpenCapture openCapture = envelope.open();

        OwnerCapture<SpanWatchdogProcessor> watchdogCapture = OwnerCapture.of(watchdogSlot);
        OwnerCapture<PlatformCompositeSpanProcessor> compositeCapture = OwnerCapture.of(compositeSlot);
        OwnerCapture<SpanLimitsMetricsProcessor> metricsCapture = OwnerCapture.of(metricsSlot);

        CompositeData watchdogValues = buildWatchdogValues(watchdogCapture.state(), watchdogCapture.offsetNanos());
        CompositeData compositeValues = buildCompositeValues(compositeCapture.state(), compositeCapture.offsetNanos());
        CompositeData metricsValues = buildMetricsValues(metricsCapture.state(), metricsCapture.offsetNanos());

        Map<String, Object> values = new LinkedHashMap<>();
        openCapture.sealInto(values);
        values.put(JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_WATCHDOG, watchdogValues);
        values.put(JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_COMPOSITE, compositeValues);
        values.put(JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_METRICS, metricsValues);
        snapshotStore.publish(values);
    }

    private static CompositeData buildWatchdogValues(OwnerRegistrationState<SpanWatchdogProcessor> state, long offsetNanos) {
        if (state instanceof OwnerRegistrationState.Present<SpanWatchdogProcessor>(
                SpanWatchdogProcessor owner, long generation
        )) {
            return watchdogValues(
                    true,
                    generation,
                    offsetNanos,
                    owner.getForcedSpanCloses(),
                    owner.getForcedTraceCloses(),
                    owner.getActiveSpanCount(),
                    owner.getActiveTraceCount(),
                    owner.getTrackedTraceCount(),
                    owner.getTrackedTraceCapacity(),
                    owner.getTrackingEvictedTotal(),
                    owner.getTrackedSpanCapacity(),
                    owner.getTrackedSpanPerTraceCapacity(),
                    owner.getSpansRejectedPerTraceTotal(),
                    owner.getSpansRejectedGlobalTotal(),
                    owner.getSpanLedgerInvariantViolationTotal());
        }

        return watchdogValues(false, state.generation(), offsetNanos,
                0L, 0L, 0, 0, 0, 0, 0L, 0, 0, 0L, 0L, 0L);
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

            return compositeValues(true, generation, offsetNanos, total, errorsByName);
        }

        return compositeValues(false, state.generation(), offsetNanos, 0L, Map.of());
    }

    private static CompositeData buildMetricsValues(OwnerRegistrationState<SpanLimitsMetricsProcessor> state, long offsetNanos) {
        if (state instanceof OwnerRegistrationState.Present<SpanLimitsMetricsProcessor>(
                SpanLimitsMetricsProcessor owner, long generation
        )) {
            return metricsValues(true, generation, offsetNanos,
                    owner.getDroppedAttributes(), owner.getDroppedEvents(), owner.getDroppedLinks());
        }

        return metricsValues(false, state.generation(), offsetNanos, 0L, 0L, 0L);
    }

    private static CompositeData watchdogValues(boolean present,
                                                long generation,
                                                long offsetNanos,
                                                long forcedSpanCloses,
                                                long forcedTraceCloses,
                                                int activeSpanCount,
                                                int activeTraceCount,
                                                int trackedTraceCount,
                                                int trackedTraceCapacity,
                                                long trackingEvictedTotal,
                                                int trackedSpanCapacity,
                                                int trackedSpanPerTraceCapacity,
                                                long spansRejectedPerTraceTotal,
                                                long spansRejectedGlobalTotal,
                                                long spanLedgerInvariantViolationTotal) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_OWNER_PRESENT, present);
        values.put(JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_OWNER_GENERATION, generation);
        values.put(JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_OWNER_CAPTURED_OFFSET_NANOS,
                offsetNanos);
        values.put(JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_FORCED_SPAN_CLOSES,
                forcedSpanCloses);
        values.put(JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_FORCED_TRACE_CLOSES,
                forcedTraceCloses);
        values.put(JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_ACTIVE_SPAN_COUNT, activeSpanCount);
        values.put(JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_ACTIVE_TRACE_COUNT, activeTraceCount);
        values.put(JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_TRACKED_TRACE_COUNT, trackedTraceCount);
        values.put(JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_TRACKED_TRACE_CAPACITY,
                trackedTraceCapacity);
        values.put(JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_TRACKING_EVICTED_TOTAL,
                trackingEvictedTotal);
        values.put(JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_TRACKED_SPAN_CAPACITY,
                trackedSpanCapacity);
        values.put(JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_TRACKED_SPAN_PER_TRACE_CAPACITY,
                trackedSpanPerTraceCapacity);
        values.put(JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_SPANS_REJECTED_PER_TRACE_TOTAL,
                spansRejectedPerTraceTotal);
        values.put(JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_SPANS_REJECTED_GLOBAL_TOTAL,
                spansRejectedGlobalTotal);
        values.put(JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_SPAN_LEDGER_INVARIANT_VIOLATION_TOTAL,
                spanLedgerInvariantViolationTotal);
        return JmxOpenTypeSupport.compositeData(JmxOpenTypes.PROCESSOR_WATCHDOG_VALUES, values);
    }

    private static CompositeData compositeValues(boolean present,
                                                 long generation,
                                                 long offsetNanos,
                                                 long processorErrorsTotal,
                                                 Map<String, Long> errorsByName) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(JmxWireContract.FIELD_PROCESSOR_COMPOSITE_VALUES_OWNER_PRESENT, present);
        values.put(JmxWireContract.FIELD_PROCESSOR_COMPOSITE_VALUES_OWNER_GENERATION, generation);
        values.put(JmxWireContract.FIELD_PROCESSOR_COMPOSITE_VALUES_OWNER_CAPTURED_OFFSET_NANOS,
                offsetNanos);
        values.put(JmxWireContract.FIELD_PROCESSOR_COMPOSITE_VALUES_PROCESSOR_ERRORS_TOTAL,
                processorErrorsTotal);
        values.put(JmxWireContract.FIELD_PROCESSOR_COMPOSITE_VALUES_PROCESSOR_ERRORS_BY_NAME,
                JmxOpenTypeSupport.stringLongMap(
                        JmxOpenTypes.PROCESSOR_ERRORS_BY_NAME,
                        JmxWireContract.FIELD_PROCESSOR_ERRORS_BY_NAME_KEY,
                        JmxWireContract.FIELD_PROCESSOR_ERRORS_BY_NAME_VALUE,
                        errorsByName));
        return JmxOpenTypeSupport.compositeData(JmxOpenTypes.PROCESSOR_COMPOSITE_VALUES, values);
    }

    private static CompositeData metricsValues(boolean present,
                                               long generation,
                                               long offsetNanos,
                                               long droppedAttributesTotal,
                                               long droppedEventsTotal,
                                               long droppedLinksTotal) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(JmxWireContract.FIELD_PROCESSOR_METRICS_VALUES_OWNER_PRESENT, present);
        values.put(JmxWireContract.FIELD_PROCESSOR_METRICS_VALUES_OWNER_GENERATION, generation);
        values.put(JmxWireContract.FIELD_PROCESSOR_METRICS_VALUES_OWNER_CAPTURED_OFFSET_NANOS,
                offsetNanos);
        values.put(JmxWireContract.FIELD_PROCESSOR_METRICS_VALUES_DROPPED_ATTRIBUTES_TOTAL,
                droppedAttributesTotal);
        values.put(JmxWireContract.FIELD_PROCESSOR_METRICS_VALUES_DROPPED_EVENTS_TOTAL,
                droppedEventsTotal);
        values.put(JmxWireContract.FIELD_PROCESSOR_METRICS_VALUES_DROPPED_LINKS_TOTAL,
                droppedLinksTotal);
        return JmxOpenTypeSupport.compositeData(JmxOpenTypes.PROCESSOR_METRICS_VALUES, values);
    }
}
