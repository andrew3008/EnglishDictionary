package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export.ExportObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export.ExportProcessorValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export.SafeExporterValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxObservationWireContract;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxAttribute;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxContractPoint;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxMBeanRegistrationState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxObservationCodec;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxReadExecutor;

import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import java.time.Duration;
import java.util.Optional;

public final class ExportJmxView {

    private static final JmxAttribute<ExportObservation> OBSERVATION = new JmxAttribute<>(
            "ExportObservation", JmxContractPoint.EXPORT_OBSERVATION, ExportJmxView::decode
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
        JmxObservationCodec.requireCompositeTypeName(composite, JmxObservationWireContract.EXPORT_COMPOSITE_TYPE_NAME);
        JmxObservationCodec.requireSchemaVersion(composite);
        String agentInstanceId = JmxObservationCodec.getString(composite, JmxObservationWireContract.ITEM_AGENT_INSTANCE_ID);

        CompositeData processorComposite = JmxObservationCodec.getComposite(composite, JmxObservationWireContract.EXPORT_ITEM_PROCESSOR);
        JmxObservationCodec.requireCompositeTypeName(processorComposite, JmxObservationWireContract.EXPORT_PROCESSOR_VALUES_TYPE_NAME);
        CompositeData exporterComposite = JmxObservationCodec.getComposite(composite, JmxObservationWireContract.EXPORT_ITEM_EXPORTER);
        JmxObservationCodec.requireCompositeTypeName(exporterComposite, JmxObservationWireContract.EXPORT_EXPORTER_VALUES_TYPE_NAME);

        JmxOwnerState<ExportProcessorValues> processorState = JmxObservationCodec.ownerState(processorComposite, () -> new ExportProcessorValues(
                JmxObservationCodec.getInt(processorComposite, JmxObservationWireContract.EXPORT_PROCESSOR_ITEM_QUEUE_CAPACITY),
                JmxObservationCodec.getInt(processorComposite, JmxObservationWireContract.EXPORT_PROCESSOR_ITEM_QUEUE_SIZE),
                JmxObservationCodec.getLong(processorComposite, JmxObservationWireContract.EXPORT_PROCESSOR_ITEM_DROPPED_OVERFLOW_TOTAL),
                JmxObservationCodec.getLong(processorComposite, JmxObservationWireContract.EXPORT_PROCESSOR_ITEM_DROPPED_AFTER_SHUTDOWN_TOTAL),
                JmxObservationCodec.getLong(processorComposite, JmxObservationWireContract.EXPORT_PROCESSOR_ITEM_FAILURES_TOTAL),
                JmxObservationCodec.getLong(processorComposite, JmxObservationWireContract.EXPORT_PROCESSOR_ITEM_TIMEOUTS_TOTAL)));

        JmxOwnerState<SafeExporterValues> exporterState = JmxObservationCodec.ownerState(exporterComposite, () -> new SafeExporterValues(
                JmxObservationCodec.getBoolean(exporterComposite, JmxObservationWireContract.EXPORT_EXPORTER_ITEM_ENABLED),
                JmxObservationCodec.getLong(exporterComposite, JmxObservationWireContract.EXPORT_EXPORTER_ITEM_LAST_DURATION_NANOS),
                JmxObservationCodec.getStringLongMap(exporterComposite, JmxObservationWireContract.EXPORT_EXPORTER_ITEM_METRICS)));

        return new ExportObservation(agentInstanceId, processorState, exporterState);
    }
}
