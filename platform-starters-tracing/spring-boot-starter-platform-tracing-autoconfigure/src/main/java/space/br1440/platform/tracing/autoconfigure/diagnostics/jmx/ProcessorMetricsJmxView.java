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
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.ObservationCapture;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

public final class ProcessorMetricsJmxView {

    private static final JmxAttribute<ProcessorMetricsObservation> OBSERVATION = new JmxAttribute<>(
            JmxWireContract.ATTRIBUTE_PROCESSOR_METRICS_OBSERVATION_PROCESSOR_METRICS_OBSERVATION,
            JmxContractPoint.PROCESSOR_METRICS_OBSERVATION,
            JmxOpenTypes.PROCESSOR_METRICS_OBSERVATION,
            ProcessorMetricsJmxView::decode
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
        JmxObservationCodec.requireCompositeTypeName(composite, JmxWireContract.OPEN_TYPE_PROCESSOR_METRICS_OBSERVATION_TYPE_NAME);
        ObservationCapture capture = new ObservationCapture(
                JmxObservationCodec.getString(composite, JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_AGENT_INSTANCE_ID),
                JmxObservationCodec.getPositiveLong(composite, JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_CAPTURE_SEQUENCE),
                JmxObservationCodec.getPositiveLong(composite, JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_CAPTURE_STARTED_AT),
                JmxObservationCodec.getPositiveLong(composite, JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_CAPTURE_COMPLETED_AT)
        );

        CompositeData watchdogComposite = JmxObservationCodec.getComposite(composite, JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_WATCHDOG);
        JmxObservationCodec.requireCompositeTypeName(watchdogComposite, JmxWireContract.OPEN_TYPE_PROCESSOR_WATCHDOG_VALUES_TYPE_NAME);

        CompositeData compositeValuesComposite = JmxObservationCodec.getComposite(composite, JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_COMPOSITE);
        JmxObservationCodec.requireCompositeTypeName(compositeValuesComposite, JmxWireContract.OPEN_TYPE_PROCESSOR_COMPOSITE_VALUES_TYPE_NAME);

        CompositeData metricsComposite = JmxObservationCodec.getComposite(composite, JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_METRICS);
        JmxObservationCodec.requireCompositeTypeName(metricsComposite, JmxWireContract.OPEN_TYPE_PROCESSOR_METRICS_VALUES_TYPE_NAME);

        JmxOwnerState<ProcessorWatchdogValues> watchdogState = JmxObservationCodec.ownerState(
                watchdogComposite,
                JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_OWNER_PRESENT,
                JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_OWNER_GENERATION,
                () -> new ProcessorWatchdogValues(
                        JmxObservationCodec.getLong(watchdogComposite, JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_FORCED_SPAN_CLOSES),

                        JmxObservationCodec.getLong(watchdogComposite, JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_FORCED_TRACE_CLOSES),

                        JmxObservationCodec.getInt(watchdogComposite, JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_ACTIVE_SPAN_COUNT),

                        JmxObservationCodec.getInt(watchdogComposite, JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_ACTIVE_TRACE_COUNT),

                        JmxObservationCodec.getInt(watchdogComposite, JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_TRACKED_TRACE_COUNT),

                        JmxObservationCodec.getInt(watchdogComposite, JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_TRACKED_TRACE_CAPACITY),

                        JmxObservationCodec.getLong(watchdogComposite, JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_TRACKING_EVICTED_TOTAL),

                        JmxObservationCodec.getInt(watchdogComposite, JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_TRACKED_SPAN_CAPACITY),

                        JmxObservationCodec.getInt(watchdogComposite, JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_TRACKED_SPAN_PER_TRACE_CAPACITY),

                        JmxObservationCodec.getLong(watchdogComposite, JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_SPANS_REJECTED_PER_TRACE_TOTAL),

                        JmxObservationCodec.getLong(watchdogComposite, JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_SPANS_REJECTED_GLOBAL_TOTAL),

                        JmxObservationCodec.getLong(watchdogComposite, JmxWireContract.FIELD_PROCESSOR_WATCHDOG_VALUES_SPAN_LEDGER_INVARIANT_VIOLATION_TOTAL))
        );

        JmxOwnerState<ProcessorCompositeValues> compositeState = JmxObservationCodec.ownerState(
                compositeValuesComposite,
                JmxWireContract.FIELD_PROCESSOR_COMPOSITE_VALUES_OWNER_PRESENT,
                JmxWireContract.FIELD_PROCESSOR_COMPOSITE_VALUES_OWNER_GENERATION,
                () -> new ProcessorCompositeValues(
                        JmxObservationCodec.getLong(
                                compositeValuesComposite,
                                JmxWireContract.FIELD_PROCESSOR_COMPOSITE_VALUES_PROCESSOR_ERRORS_TOTAL
                        ),

                        JmxObservationCodec.getStringLongMap(
                                compositeValuesComposite,
                                JmxWireContract.FIELD_PROCESSOR_COMPOSITE_VALUES_PROCESSOR_ERRORS_BY_NAME,
                                JmxWireContract.FIELD_PROCESSOR_ERRORS_BY_NAME_KEY,
                                JmxWireContract.FIELD_PROCESSOR_ERRORS_BY_NAME_VALUE
                        )
                )
        );

        JmxOwnerState<ProcessorMetricsValues> metricsState = JmxObservationCodec.ownerState(
                metricsComposite,
                JmxWireContract.FIELD_PROCESSOR_METRICS_VALUES_OWNER_PRESENT,
                JmxWireContract.FIELD_PROCESSOR_METRICS_VALUES_OWNER_GENERATION,
                () -> new ProcessorMetricsValues(
                        JmxObservationCodec.getLong(metricsComposite, JmxWireContract.FIELD_PROCESSOR_METRICS_VALUES_DROPPED_ATTRIBUTES_TOTAL),

                        JmxObservationCodec.getLong(metricsComposite, JmxWireContract.FIELD_PROCESSOR_METRICS_VALUES_DROPPED_EVENTS_TOTAL),

                        JmxObservationCodec.getLong(metricsComposite, JmxWireContract.FIELD_PROCESSOR_METRICS_VALUES_DROPPED_LINKS_TOTAL)
                )
        );

        return new ProcessorMetricsObservation(capture, watchdogState, compositeState, metricsState);
    }
}
