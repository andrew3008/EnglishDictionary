package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx;

import java.time.Duration;
import java.util.Optional;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export.ExportObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export.ExportProcessorValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export.SafeExporterValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxAttribute;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxContractPoint;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxMBeanRegistrationState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxObservationCodec;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxReadExecutor;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

public final class ExportJmxView {

    private static final JmxAttribute<ExportObservation> OBSERVATION = new JmxAttribute<>(
            JmxWireContract.ATTRIBUTE_EXPORT_OBSERVATION_EXPORT_OBSERVATION,
            JmxContractPoint.EXPORT_OBSERVATION,
            JmxOpenTypes.EXPORT_OBSERVATION,
            ExportJmxView::decode,
            ExportObservation::agentInstanceId
    );

    private final JmxReadExecutor executor;
    private final ObjectName objectName;

    ExportJmxView(JmxReadExecutor executor, ObjectName objectName) {
        this.executor = executor;
        this.objectName = objectName;
    }

    public JmxMBeanRegistrationState registration() {
        return executor.registration(objectName, JmxContractPoint.EXPORT_REGISTRATION);
    }

    public Optional<ExportObservation> observation() {
        return executor.read(objectName, OBSERVATION);
    }

    public Optional<Duration> lastSuccessAge() {
        return executor.lastSuccessAge(OBSERVATION.point());
    }

    private static ExportObservation decode(Object raw) {
        CompositeData composite = JmxObservationCodec.asComposite(raw);
        JmxObservationCodec.requireCompositeTypeName(composite, JmxWireContract.OPEN_TYPE_EXPORT_OBSERVATION_TYPE_NAME);
        String agentInstanceId = JmxObservationCodec.getAgentInstanceId(
                composite,
                JmxWireContract.FIELD_EXPORT_OBSERVATION_AGENT_INSTANCE_ID
        );

        CompositeData processorComposite = JmxObservationCodec.getComposite(composite, JmxWireContract.FIELD_EXPORT_OBSERVATION_PROCESSOR);
        JmxObservationCodec.requireCompositeTypeName(processorComposite, JmxWireContract.OPEN_TYPE_EXPORT_PROCESSOR_VALUES_TYPE_NAME);
        CompositeData exporterComposite = JmxObservationCodec.getComposite(composite, JmxWireContract.FIELD_EXPORT_OBSERVATION_EXPORTER);
        JmxObservationCodec.requireCompositeTypeName(exporterComposite, JmxWireContract.OPEN_TYPE_SAFE_EXPORTER_VALUES_TYPE_NAME);

        JmxOwnerState<ExportProcessorValues> processorState = JmxObservationCodec.ownerState(
                processorComposite,
                JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_OWNER_PRESENT,
                JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_OWNER_GENERATION,
                () -> new ExportProcessorValues(
                        JmxObservationCodec.getInt(processorComposite,
                                JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_QUEUE_CAPACITY),
                        JmxObservationCodec.getInt(processorComposite,
                                JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_QUEUE_SIZE),
                        JmxObservationCodec.getLong(processorComposite,
                                JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_DROPPED_OVERFLOW_TOTAL),
                        JmxObservationCodec.getLong(processorComposite,
                                JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_DROPPED_AFTER_SHUTDOWN_TOTAL),
                        JmxObservationCodec.getLong(processorComposite,
                                JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_FAILURES_TOTAL),
                        JmxObservationCodec.getLong(processorComposite,
                                JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_TIMEOUTS_TOTAL),
                        JmxObservationCodec.getEnum(processorComposite,
                                JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_LIFECYCLE_STATE,
                                ExportProcessorValues.LifecycleState.class),
                        JmxObservationCodec.getLong(processorComposite,
                                JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_SHUTDOWN_FAILURES_TOTAL),
                        JmxObservationCodec.getLong(processorComposite,
                                JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_DROPPED_AFTER_FAILURE_TOTAL),
                        JmxObservationCodec.getLong(processorComposite,
                                JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_DISCARDED_QUEUED_TOTAL)));

        JmxOwnerState<SafeExporterValues> exporterState = JmxObservationCodec.ownerState(
                exporterComposite,
                JmxWireContract.FIELD_SAFE_EXPORTER_VALUES_OWNER_PRESENT,
                JmxWireContract.FIELD_SAFE_EXPORTER_VALUES_OWNER_GENERATION,
                () -> new SafeExporterValues(
                        JmxObservationCodec.getStringLongMap(
                                exporterComposite,
                                JmxWireContract.FIELD_SAFE_EXPORTER_VALUES_SAFE_EXPORTER_METRICS,
                                JmxWireContract.FIELD_SAFE_EXPORTER_METRICS_KEY,
                                JmxWireContract.FIELD_SAFE_EXPORTER_METRICS_VALUE
                        )
                )
        );

        return new ExportObservation(agentInstanceId, processorState, exporterState);
    }
}
