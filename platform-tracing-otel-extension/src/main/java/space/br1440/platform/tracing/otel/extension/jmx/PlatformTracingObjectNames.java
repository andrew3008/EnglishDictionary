package space.br1440.platform.tracing.otel.extension.jmx;

import lombok.experimental.UtilityClass;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

@UtilityClass
public final class PlatformTracingObjectNames {

    public static final String SAMPLING_OBJECT_NAME_STR = "space.br1440.platform.tracing:type=SamplingControl,name=PlatformSamplingControl";
    public static final String SCRUBBING_OBJECT_NAME_STR = "space.br1440.platform.tracing:type=ScrubbingControl,name=PlatformScrubbingControl";
    public static final String VALIDATION_OBJECT_NAME_STR = "space.br1440.platform.tracing:type=ValidationControl,name=PlatformValidationControl";
    public static final String EXPORT_OBJECT_NAME_STR = "space.br1440.platform.tracing:type=ExportControl,name=PlatformExportControl";
    public static final String PROCESSOR_METRICS_OBJECT_NAME_STR = "space.br1440.platform.tracing:type=ProcessorMetricsControl,name=PlatformProcessorMetricsControl";
    public static final String DIAGNOSTICS_OBJECT_NAME_STR = "space.br1440.platform.tracing:type=DiagnosticsControl,name=PlatformDiagnosticsControl";

    public static final ObjectName SAMPLING;
    public static final ObjectName SCRUBBING;
    public static final ObjectName VALIDATION;
    public static final ObjectName EXPORT;
    public static final ObjectName PROCESSOR_METRICS;
    public static final ObjectName DIAGNOSTICS;

    static {
        try {
            SAMPLING = ObjectName.getInstance(SAMPLING_OBJECT_NAME_STR);
            SCRUBBING = ObjectName.getInstance(SCRUBBING_OBJECT_NAME_STR);
            VALIDATION = ObjectName.getInstance(VALIDATION_OBJECT_NAME_STR);
            EXPORT = ObjectName.getInstance(EXPORT_OBJECT_NAME_STR);
            PROCESSOR_METRICS = ObjectName.getInstance(PROCESSOR_METRICS_OBJECT_NAME_STR);
            DIAGNOSTICS = ObjectName.getInstance(DIAGNOSTICS_OBJECT_NAME_STR);
        } catch (MalformedObjectNameException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
