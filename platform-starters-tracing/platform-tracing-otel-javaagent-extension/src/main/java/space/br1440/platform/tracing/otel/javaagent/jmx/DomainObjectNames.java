package space.br1440.platform.tracing.otel.javaagent.jmx;

import javax.management.ObjectName;

import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxWireContract;

import lombok.experimental.UtilityClass;

/**
 * Короткие runtime-алиасы, полностью производные от generated wire-контракта.
 */
@UtilityClass
public final class DomainObjectNames {

    public static final ObjectName SAMPLING = GeneratedJmxWireContract.SAMPLING_OBSERVATION_OBJECT_NAME;
    public static final ObjectName SCRUBBING = GeneratedJmxWireContract.SCRUBBING_OBSERVATION_OBJECT_NAME;
    public static final ObjectName VALIDATION = GeneratedJmxWireContract.VALIDATION_OBSERVATION_OBJECT_NAME;
    public static final ObjectName EXPORT = GeneratedJmxWireContract.EXPORT_OBSERVATION_OBJECT_NAME;
    public static final ObjectName PROCESSOR_METRICS =
            GeneratedJmxWireContract.PROCESSOR_METRICS_OBSERVATION_OBJECT_NAME;
    public static final ObjectName DIAGNOSTICS = GeneratedJmxWireContract.DIAGNOSTICS_OBSERVATION_OBJECT_NAME;
    public static final ObjectName SETTINGS_STATUS = GeneratedJmxWireContract.SETTINGS_STATUS_OBJECT_NAME;
    public static final ObjectName SETTINGS_MUTATION = GeneratedJmxWireContract.SETTINGS_MUTATION_OBJECT_NAME;
    public static final ObjectName EXTENSION_READINESS = GeneratedJmxWireContract.EXTENSION_READINESS_OBJECT_NAME;
}
