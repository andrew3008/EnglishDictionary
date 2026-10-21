package space.br1440.platform.tracing.otel.javaagent.jmx;

import javax.management.ObjectName;

import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class DomainObjectNames {

    public static final ObjectName SAMPLING = JmxWireContract.SAMPLING_OBSERVATION_OBJECT_NAME;
    public static final ObjectName SCRUBBING = JmxWireContract.SCRUBBING_OBSERVATION_OBJECT_NAME;
    public static final ObjectName VALIDATION = JmxWireContract.VALIDATION_OBSERVATION_OBJECT_NAME;
    public static final ObjectName EXPORT = JmxWireContract.EXPORT_OBSERVATION_OBJECT_NAME;
    public static final ObjectName PROCESSOR_METRICS = JmxWireContract.PROCESSOR_METRICS_OBSERVATION_OBJECT_NAME;
    public static final ObjectName DIAGNOSTICS = JmxWireContract.DIAGNOSTICS_OBSERVATION_OBJECT_NAME;
    public static final ObjectName SETTINGS_STATUS = JmxWireContract.SETTINGS_STATUS_OBJECT_NAME;
    public static final ObjectName SETTINGS_MUTATION = JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME;
    public static final ObjectName EXTENSION_READINESS = JmxWireContract.EXTENSION_READINESS_OBJECT_NAME;

}
