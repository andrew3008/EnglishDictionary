package space.br1440.platform.tracing.autoconfigure.support;

import java.util.function.Supplier;
import javax.management.DynamicMBean;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

public final class DomainSnapshotEndpoints {

    public static DynamicMBean sampling(Supplier<CompositeData> snapshotSupplier) {
        return new ReadOnlyOpenSnapshotEndpoint(
                JmxWireContract.ATTRIBUTE_SAMPLING_OBSERVATION_SAMPLING_OBSERVATION,
                JmxOpenTypes.SAMPLING_OBSERVATION,
                snapshotSupplier
        );
    }

    public static DynamicMBean scrubbing(Supplier<CompositeData> snapshotSupplier) {
        return new ReadOnlyOpenSnapshotEndpoint(
                JmxWireContract.ATTRIBUTE_SCRUBBING_OBSERVATION_SCRUBBING_OBSERVATION,
                JmxOpenTypes.SCRUBBING_OBSERVATION,
                snapshotSupplier
        );
    }

    public static DynamicMBean validation(Supplier<CompositeData> snapshotSupplier) {
        return new ReadOnlyOpenSnapshotEndpoint(
                JmxWireContract.ATTRIBUTE_VALIDATION_OBSERVATION_VALIDATION_OBSERVATION,
                JmxOpenTypes.VALIDATION_OBSERVATION,
                snapshotSupplier
        );
    }

    public static DynamicMBean export(Supplier<CompositeData> snapshotSupplier) {
        return new ReadOnlyOpenSnapshotEndpoint(
                JmxWireContract.ATTRIBUTE_EXPORT_OBSERVATION_EXPORT_OBSERVATION,
                JmxOpenTypes.EXPORT_OBSERVATION,
                snapshotSupplier
        );
    }

    public static DynamicMBean processorMetrics(Supplier<CompositeData> snapshotSupplier) {
        return new ReadOnlyOpenSnapshotEndpoint(
                JmxWireContract.ATTRIBUTE_PROCESSOR_METRICS_OBSERVATION_PROCESSOR_METRICS_OBSERVATION,
                JmxOpenTypes.PROCESSOR_METRICS_OBSERVATION,
                snapshotSupplier
        );
    }

    public static DynamicMBean diagnostics(Supplier<CompositeData> snapshotSupplier) {
        return new ReadOnlyOpenSnapshotEndpoint(
                JmxWireContract.ATTRIBUTE_DIAGNOSTICS_OBSERVATION_DIAGNOSTICS_OBSERVATION,
                JmxOpenTypes.DIAGNOSTICS_OBSERVATION,
                snapshotSupplier
        );
    }
}
