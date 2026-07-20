package space.br1440.platform.tracing.autoconfigure.jmx;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

/**
 * Дублирует канонические строки ObjectName из {@code PlatformTracingObjectNames} модуля
 * {@code platform-tracing-otel-extension}. Модули работают в разных classloader'ах —
 * дублирование намеренно; их совпадение гарантируется тестом {@code ObjectNameConsistencyTest}.
 */
public final class PlatformTracingJmxObjectNames {

    public static final String SAMPLING_OBJECT_NAME_STR =
            "space.br1440.platform.tracing:type=Sampling,name=PlatformSamplingControl";
    public static final String SCRUBBING_OBJECT_NAME_STR =
            "space.br1440.platform.tracing:type=Scrubbing,name=PlatformScrubbingControl";
    public static final String VALIDATION_OBJECT_NAME_STR =
            "space.br1440.platform.tracing:type=Validation,name=PlatformValidationControl";
    public static final String EXPORT_OBJECT_NAME_STR =
            "space.br1440.platform.tracing:type=Export,name=PlatformExportControl";
    public static final String PROCESSOR_METRICS_OBJECT_NAME_STR =
            "space.br1440.platform.tracing:type=Metrics,name=PlatformProcessorMetricsControl";
    public static final String DIAGNOSTICS_OBJECT_NAME_STR =
            "space.br1440.platform.tracing:type=Diagnostics,name=PlatformDiagnosticsControl";
    public static final String EXTENSION_READINESS_OBJECT_NAME_STR =
            "space.br1440.platform.tracing:type=Readiness,name=PlatformExtension";

    public static final ObjectName SAMPLING;
    public static final ObjectName SCRUBBING;
    public static final ObjectName VALIDATION;
    public static final ObjectName EXPORT;
    public static final ObjectName PROCESSOR_METRICS;
    public static final ObjectName DIAGNOSTICS;
    public static final ObjectName EXTENSION_READINESS;

    static {
        try {
            SAMPLING = ObjectName.getInstance(SAMPLING_OBJECT_NAME_STR);
            SCRUBBING = ObjectName.getInstance(SCRUBBING_OBJECT_NAME_STR);
            VALIDATION = ObjectName.getInstance(VALIDATION_OBJECT_NAME_STR);
            EXPORT = ObjectName.getInstance(EXPORT_OBJECT_NAME_STR);
            PROCESSOR_METRICS = ObjectName.getInstance(PROCESSOR_METRICS_OBJECT_NAME_STR);
            DIAGNOSTICS = ObjectName.getInstance(DIAGNOSTICS_OBJECT_NAME_STR);
            EXTENSION_READINESS = ObjectName.getInstance(EXTENSION_READINESS_OBJECT_NAME_STR);
        } catch (MalformedObjectNameException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private PlatformTracingJmxObjectNames() {
    }
}
