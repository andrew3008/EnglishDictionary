package space.br1440.platform.tracing.otel.javaagent.jmx.metrics;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxOpenTypeSupport;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationCaptureEnvelope;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationSnapshotStore;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerValuesLayout;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerValuesLayout.Field;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerValuesLayout.OwnerHeader;
import space.br1440.platform.tracing.otel.javaagent.processor.SpanLimitsMetricsProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.spantimeout.SpanTimeoutProcessor;

public final class ProcessorMetricsObservationSource {

    private static final OwnerValuesLayout<SpanTimeoutProcessor> SPAN_TIMEOUT_LAYOUT = OwnerValuesLayout.of(
            JmxOpenTypes.PROCESSOR_SPAN_TIMEOUT_VALUES,

            new OwnerHeader(
                    JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_OWNER_PRESENT,
                    JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_OWNER_GENERATION
            ),

            List.of(
                    Field.ofLong(JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_FORCED_SPAN_END_ATTEMPTS_TOTAL, SpanTimeoutProcessor::getForcedSpanEndAttemptsTotal),
                    Field.ofLong(JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_FORCED_TRACE_END_ATTEMPTS_TOTAL, SpanTimeoutProcessor::getForcedTraceEndAttemptsTotal),

                    Field.ofInt(JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_ACTIVE_SPAN_COUNT, SpanTimeoutProcessor::getActiveSpanCount),
                    Field.ofInt(JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_ACTIVE_TRACE_COUNT, SpanTimeoutProcessor::getActiveTraceCount),

                    Field.ofInt(JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_TRACKED_TRACE_COUNT, SpanTimeoutProcessor::getTrackedTraceCount),
                    Field.ofInt(JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_TRACKED_TRACE_CAPACITY, SpanTimeoutProcessor::getTrackedTraceCapacity),
                    Field.ofLong(JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_TRACKING_EVICTED_TOTAL, SpanTimeoutProcessor::getTrackingEvictedTotal),
                    Field.ofInt(JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_TRACKED_SPAN_CAPACITY, SpanTimeoutProcessor::getTrackedSpanCapacity),
                    Field.ofInt(JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_TRACKED_SPAN_PER_TRACE_CAPACITY, SpanTimeoutProcessor::getTrackedSpanPerTraceCapacity),

                    Field.ofLong(JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_SPANS_REJECTED_PER_TRACE_TOTAL, SpanTimeoutProcessor::getSpansRejectedPerTraceTotal),
                    Field.ofLong(JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_SPANS_REJECTED_GLOBAL_TOTAL, SpanTimeoutProcessor::getSpansRejectedGlobalTotal),
                    Field.ofLong(JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_SPANS_REJECTED_TRACE_CAPACITY_TOTAL, SpanTimeoutProcessor::getSpansRejectedTraceCapacityTotal),

                    Field.ofLong(JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_SPAN_LEDGER_INVARIANT_VIOLATION_TOTAL, SpanTimeoutProcessor::getSpanPermitInvariantViolationTotal),
                    Field.ofLong(JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_SCAN_FAILURES_TOTAL, SpanTimeoutProcessor::getScanFailuresTotal),
                    Field.ofLong(JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_FORCE_END_FAILURES_TOTAL, SpanTimeoutProcessor::getForceEndFailuresTotal),
                    Field.ofLong(JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_LAST_SUCCESSFUL_BACKGROUND_SCAN_EPOCH_MILLIS, SpanTimeoutProcessor::getLastSuccessfulBackgroundScanEpochMillis),
                    Field.ofString(JmxWireContract.FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_SCANNER_STATE, owner -> owner.getScannerState().name())
            )
    );

    private static final OwnerValuesLayout<Map<String, Long>> COMPOSITE_LAYOUT = OwnerValuesLayout.of(
            JmxOpenTypes.PROCESSOR_COMPOSITE_VALUES,

            new OwnerHeader(
                    JmxWireContract.FIELD_PROCESSOR_COMPOSITE_VALUES_OWNER_PRESENT,
                    JmxWireContract.FIELD_PROCESSOR_COMPOSITE_VALUES_OWNER_GENERATION
            ),

            List.of(
                    Field.ofLong(JmxWireContract.FIELD_PROCESSOR_COMPOSITE_VALUES_PROCESSOR_ERRORS_TOTAL, ProcessorMetricsObservationSource::sumErrors),
                    new Field<>(
                            JmxWireContract.FIELD_PROCESSOR_COMPOSITE_VALUES_PROCESSOR_ERRORS_BY_NAME,
                            ProcessorMetricsObservationSource::errorsByNameTable, errorsByNameTable(Map.of())
                    )
            )
    );

    private static final OwnerValuesLayout<SpanLimitsMetricsProcessor> METRICS_LAYOUT = OwnerValuesLayout.of(
            JmxOpenTypes.PROCESSOR_METRICS_VALUES,
            new OwnerHeader(
                    JmxWireContract.FIELD_PROCESSOR_METRICS_VALUES_OWNER_PRESENT,
                    JmxWireContract.FIELD_PROCESSOR_METRICS_VALUES_OWNER_GENERATION
            ),

            List.of(
                    Field.ofLong(JmxWireContract.FIELD_PROCESSOR_METRICS_VALUES_DROPPED_ATTRIBUTES_TOTAL, SpanLimitsMetricsProcessor::getDroppedAttributes),
                    Field.ofLong(JmxWireContract.FIELD_PROCESSOR_METRICS_VALUES_DROPPED_EVENTS_TOTAL, SpanLimitsMetricsProcessor::getDroppedEvents),
                    Field.ofLong(JmxWireContract.FIELD_PROCESSOR_METRICS_VALUES_DROPPED_LINKS_TOTAL, SpanLimitsMetricsProcessor::getDroppedLinks)
            )
    );

    private final OwnerRegistrationSlot<SpanTimeoutProcessor> spanTimeoutSlot;
    private final OwnerRegistrationSlot<PlatformCompositeSpanProcessor> compositeSlot;
    private final OwnerRegistrationSlot<SpanLimitsMetricsProcessor> metricsSlot;
    private final ObservationCaptureEnvelope envelope;
    private final AtomicLong captureSequence = new AtomicLong();
    private final ObservationSnapshotStore snapshotStore = new ObservationSnapshotStore(JmxOpenTypes.PROCESSOR_METRICS_OBSERVATION);

    public ProcessorMetricsObservationSource(OwnerRegistrationSlot<SpanTimeoutProcessor> spanTimeoutSlot,
                                             OwnerRegistrationSlot<PlatformCompositeSpanProcessor> compositeSlot,
                                             OwnerRegistrationSlot<SpanLimitsMetricsProcessor> metricsSlot,
                                             AgentInstanceId agentInstanceId) {
        this.spanTimeoutSlot = Objects.requireNonNull(spanTimeoutSlot, "spanTimeoutSlot");
        this.compositeSlot = Objects.requireNonNull(compositeSlot, "compositeSlot");
        this.metricsSlot = Objects.requireNonNull(metricsSlot, "metricsSlot");
        this.envelope = new ObservationCaptureEnvelope(agentInstanceId);
        publishObservationSnapshot();
    }

    public CompositeData getProcessorMetricsObservation() {
        return snapshotStore.current();
    }

    public synchronized void publishObservationSnapshot() {
        long sequence = captureSequence.incrementAndGet();
        long startedAtEpochMilli = Instant.now().toEpochMilli();

        CompositeData spanTimeoutValues = SPAN_TIMEOUT_LAYOUT.encode(spanTimeoutSlot.capture(), Function.identity());
        CompositeData compositeValues = COMPOSITE_LAYOUT.encode(
                compositeSlot.capture(),
                PlatformCompositeSpanProcessor::getProcessorErrorCounts
        );
        CompositeData metricsValues = METRICS_LAYOUT.encode(metricsSlot.capture(), Function.identity());

        Map<String, Object> values = new LinkedHashMap<>();
        envelope.putInto(values);
        values.put(JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_CAPTURE_SEQUENCE, sequence);
        values.put(JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_CAPTURE_STARTED_AT, startedAtEpochMilli);
        values.put(JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_SPAN_TIMEOUT, spanTimeoutValues);
        values.put(JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_COMPOSITE, compositeValues);
        values.put(JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_METRICS, metricsValues);
        values.put(JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_CAPTURE_COMPLETED_AT, Instant.now().toEpochMilli());
        snapshotStore.publish(values);
    }

    private static long sumErrors(Map<String, Long> errorsByName) {
        long total = 0L;
        for (Long value : errorsByName.values()) {
            if (value != null) {
                total += value;
            }
        }

        return total;
    }

    private static TabularData errorsByNameTable(Map<String, Long> errorsByName) {
        return JmxOpenTypeSupport.stringLongMap(
                JmxOpenTypes.PROCESSOR_ERRORS_BY_NAME,
                JmxWireContract.FIELD_PROCESSOR_ERRORS_BY_NAME_KEY,
                JmxWireContract.FIELD_PROCESSOR_ERRORS_BY_NAME_VALUE,
                errorsByName
        );
    }
}
