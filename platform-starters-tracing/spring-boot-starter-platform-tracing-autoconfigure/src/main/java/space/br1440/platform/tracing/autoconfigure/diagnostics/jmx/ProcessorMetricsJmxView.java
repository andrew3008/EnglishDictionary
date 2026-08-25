package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx;

import java.time.Duration;
import java.util.Optional;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorCompositeValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorMetricsObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorMetricsValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorWatchdogValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxAttribute;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxContractPoint;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxMBeanRegistrationState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxObservationCodec;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxReadExecutor;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxObservationWireContract;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;

public final class ProcessorMetricsJmxView {

    private static final JmxAttribute<ProcessorMetricsObservation> OBSERVATION = new JmxAttribute<>(
            "ProcessorMetricsObservation", JmxContractPoint.PROCESSOR_METRICS_OBSERVATION, ProcessorMetricsJmxView::decode
    );

    private final JmxReadExecutor executor;
    private final ObjectName objectName;

    ProcessorMetricsJmxView(JmxReadExecutor executor, ObjectName objectName) {
        this.executor = executor;
        this.objectName = objectName;
    }

    public JmxMBeanRegistrationState registration() {
        return executor.registration(objectName, JmxContractPoint.PROCESSOR_METRICS_REGISTRATION);
    }

    public Optional<ProcessorMetricsObservation> observation() {
        return executor.read(objectName, OBSERVATION);
    }

    public Optional<Duration> lastSuccessAge() {
        return executor.lastSuccessAge(OBSERVATION.point());
    }

    private static ProcessorMetricsObservation decode(Object raw) {
        CompositeData composite = JmxObservationCodec.asComposite(raw);
        JmxObservationCodec.requireCompositeTypeName(composite, JmxObservationWireContract.PROCESSOR_METRICS_COMPOSITE_TYPE_NAME);
        JmxObservationCodec.requireSchemaVersion(composite);
        String agentInstanceId = JmxObservationCodec.getString(composite, JmxObservationWireContract.ITEM_AGENT_INSTANCE_ID);

        CompositeData watchdogComposite = JmxObservationCodec.getComposite(composite, JmxObservationWireContract.PROCESSOR_METRICS_ITEM_WATCHDOG);
        JmxObservationCodec.requireCompositeTypeName(watchdogComposite, JmxObservationWireContract.PROCESSOR_METRICS_WATCHDOG_VALUES_TYPE_NAME);
        CompositeData compositeValuesComposite = JmxObservationCodec.getComposite(composite, JmxObservationWireContract.PROCESSOR_METRICS_ITEM_COMPOSITE);
        JmxObservationCodec.requireCompositeTypeName(compositeValuesComposite, JmxObservationWireContract.PROCESSOR_METRICS_COMPOSITE_VALUES_TYPE_NAME);
        CompositeData metricsComposite = JmxObservationCodec.getComposite(composite, JmxObservationWireContract.PROCESSOR_METRICS_ITEM_METRICS);
        JmxObservationCodec.requireCompositeTypeName(metricsComposite, JmxObservationWireContract.PROCESSOR_METRICS_METRICS_VALUES_TYPE_NAME);

        JmxOwnerState<ProcessorWatchdogValues> watchdogState = JmxObservationCodec.ownerState(watchdogComposite, () -> new ProcessorWatchdogValues(
                JmxObservationCodec.getLong(watchdogComposite, JmxObservationWireContract.PROCESSOR_METRICS_WATCHDOG_ITEM_FORCED_SPAN_CLOSES),
                JmxObservationCodec.getLong(watchdogComposite, JmxObservationWireContract.PROCESSOR_METRICS_WATCHDOG_ITEM_FORCED_TRACE_CLOSES),
                JmxObservationCodec.getInt(watchdogComposite, JmxObservationWireContract.PROCESSOR_METRICS_WATCHDOG_ITEM_ACTIVE_SPAN_COUNT),
                JmxObservationCodec.getInt(watchdogComposite, JmxObservationWireContract.PROCESSOR_METRICS_WATCHDOG_ITEM_ACTIVE_TRACE_COUNT),
                JmxObservationCodec.getInt(watchdogComposite, JmxObservationWireContract.PROCESSOR_METRICS_WATCHDOG_ITEM_TRACKED_TRACE_COUNT),
                JmxObservationCodec.getInt(watchdogComposite, JmxObservationWireContract.PROCESSOR_METRICS_WATCHDOG_ITEM_TRACKED_TRACE_CAPACITY),
                JmxObservationCodec.getLong(watchdogComposite, JmxObservationWireContract.PROCESSOR_METRICS_WATCHDOG_ITEM_TRACKING_EVICTED_TOTAL),
                JmxObservationCodec.getInt(watchdogComposite, JmxObservationWireContract.PROCESSOR_METRICS_WATCHDOG_ITEM_TRACKED_SPAN_CAPACITY),
                JmxObservationCodec.getInt(watchdogComposite, JmxObservationWireContract.PROCESSOR_METRICS_WATCHDOG_ITEM_TRACKED_SPAN_PER_TRACE_CAPACITY),
                JmxObservationCodec.getLong(watchdogComposite, JmxObservationWireContract.PROCESSOR_METRICS_WATCHDOG_ITEM_SPANS_REJECTED_PER_TRACE_TOTAL),
                JmxObservationCodec.getLong(watchdogComposite, JmxObservationWireContract.PROCESSOR_METRICS_WATCHDOG_ITEM_SPANS_REJECTED_GLOBAL_TOTAL),
                JmxObservationCodec.getLong(watchdogComposite, JmxObservationWireContract.PROCESSOR_METRICS_WATCHDOG_ITEM_SPAN_LEDGER_INVARIANT_VIOLATION_TOTAL)));

        JmxOwnerState<ProcessorCompositeValues> compositeState = JmxObservationCodec.ownerState(compositeValuesComposite, () -> new ProcessorCompositeValues(
                JmxObservationCodec.getLong(compositeValuesComposite, JmxObservationWireContract.PROCESSOR_METRICS_COMPOSITE_ITEM_ERRORS_TOTAL),
                JmxObservationCodec.getStringLongMap(compositeValuesComposite, JmxObservationWireContract.PROCESSOR_METRICS_COMPOSITE_ITEM_ERRORS_BY_NAME)));

        JmxOwnerState<ProcessorMetricsValues> metricsState = JmxObservationCodec.ownerState(metricsComposite, () -> new ProcessorMetricsValues(
                JmxObservationCodec.getLong(metricsComposite, JmxObservationWireContract.PROCESSOR_METRICS_METRICS_ITEM_DROPPED_ATTRIBUTES_TOTAL),
                JmxObservationCodec.getLong(metricsComposite, JmxObservationWireContract.PROCESSOR_METRICS_METRICS_ITEM_DROPPED_EVENTS_TOTAL),
                JmxObservationCodec.getLong(metricsComposite, JmxObservationWireContract.PROCESSOR_METRICS_METRICS_ITEM_DROPPED_LINKS_TOTAL)));

        return new ProcessorMetricsObservation(agentInstanceId, watchdogState, compositeState, metricsState);
    }
}
