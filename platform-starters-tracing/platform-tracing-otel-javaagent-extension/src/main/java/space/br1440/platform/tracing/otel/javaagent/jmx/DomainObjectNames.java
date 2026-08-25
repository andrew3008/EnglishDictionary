package space.br1440.platform.tracing.otel.javaagent.jmx;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import lombok.experimental.UtilityClass;

/**
 * Канонические ObjectName доменных и readiness MBean.
 * <p>
 * Значения ключа {@code name=} — стабильный операционный идентификатор (ABI для JMX-клиентов,
 * метрик, алертов, runbook и e2e). Они намеренно не производны от
 * {@code Class.getSimpleName()} и не должны синхронизироваться с переименованиями Java-классов.
 */
@UtilityClass
public final class DomainObjectNames {

    public static final String SAMPLING_OBJECT_NAME_STR = "space.br1440.platform.tracing:type=Sampling,name=PlatformSamplingControl";
    public static final String SCRUBBING_OBJECT_NAME_STR = "space.br1440.platform.tracing:type=Scrubbing,name=PlatformScrubbingControl";
    public static final String VALIDATION_OBJECT_NAME_STR = "space.br1440.platform.tracing:type=Validation,name=PlatformValidationControl";
    public static final String EXPORT_OBJECT_NAME_STR = "space.br1440.platform.tracing:type=Export,name=PlatformExportControl";
    public static final String PROCESSOR_METRICS_OBJECT_NAME_STR = "space.br1440.platform.tracing:type=Metrics,name=PlatformProcessorMetricsControl";
    public static final String DIAGNOSTICS_OBJECT_NAME_STR = "space.br1440.platform.tracing:type=Diagnostics,name=PlatformDiagnosticsControl";
    public static final String CONTROL_PROTOCOL_OBJECT_NAME_STR = "space.br1440.platform.tracing:type=Control,name=PlatformControlProtocol";
    public static final String EXTENSION_READINESS_OBJECT_NAME_STR = "space.br1440.platform.tracing:type=Readiness,name=PlatformExtension";

    public static final ObjectName SAMPLING = name(SAMPLING_OBJECT_NAME_STR);
    public static final ObjectName SCRUBBING = name(SCRUBBING_OBJECT_NAME_STR);
    public static final ObjectName VALIDATION = name(VALIDATION_OBJECT_NAME_STR);
    public static final ObjectName EXPORT = name(EXPORT_OBJECT_NAME_STR);
    public static final ObjectName PROCESSOR_METRICS = name(PROCESSOR_METRICS_OBJECT_NAME_STR);
    public static final ObjectName DIAGNOSTICS = name(DIAGNOSTICS_OBJECT_NAME_STR);
    public static final ObjectName CONTROL_PROTOCOL = name(CONTROL_PROTOCOL_OBJECT_NAME_STR);
    public static final ObjectName EXTENSION_READINESS = name(EXTENSION_READINESS_OBJECT_NAME_STR);

    private static ObjectName name(String s) {
        try {
            return new ObjectName(s);
        } catch (MalformedObjectNameException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
