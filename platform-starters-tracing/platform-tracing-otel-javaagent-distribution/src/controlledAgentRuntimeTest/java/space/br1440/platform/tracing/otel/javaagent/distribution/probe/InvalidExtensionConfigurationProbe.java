package space.br1440.platform.tracing.otel.javaagent.distribution.probe;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import javax.management.MBeanServer;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;

public final class InvalidExtensionConfigurationProbe {

    public static final String MAIN_REACHED_MARKER = "APPLICATION_MAIN_REACHED";
    public static final String RESULT_PREFIX = "INVALID_EXTENSION_CONFIGURATION_PROBE";
    public static final String SAMPLER_CAPABILITY_PREFIX = "INVALID_EXTENSION_CONFIGURATION_SAMPLER_CAPABILITY";

    private static final String PLATFORM_SAMPLER_CAPABILITY = "PLATFORM_SAMPLER_INSTALLED";

    private InvalidExtensionConfigurationProbe() {
    }

    public static void main(String[] arguments) throws Exception {
        String spanName = spanName(arguments);
        System.out.println(MAIN_REACHED_MARKER);

        Tracer tracer = GlobalOpenTelemetry.getTracer("invalid-extension-configuration-probe");
        tracer.spanBuilder(spanName).startSpan().end();

        CompositeData snapshot = readinessSnapshot(ManagementFactory.getPlatformMBeanServer());
        String lifecycle = stringField(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE);
        String failureCode = stringField(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_FAILURE_CODE);
        String[] capabilities = stringArrayField(
                snapshot,
                JmxWireContract.FIELD_READINESS_SNAPSHOT_CAPABILITIES
        );
        boolean platformSamplerInstalled = Arrays.asList(capabilities).contains(PLATFORM_SAMPLER_CAPABILITY);

        System.out.printf(
                Locale.ROOT,
                "%s lifecycle=%s failureCode=%s spanName=%s%n",
                RESULT_PREFIX,
                lifecycle,
                failureCode,
                spanName
        );

        System.out.printf(
                Locale.ROOT,
                "%s capability=%s installed=%s%n",
                SAMPLER_CAPABILITY_PREFIX,
                PLATFORM_SAMPLER_CAPABILITY,
                platformSamplerInstalled
        );
    }

    private static String spanName(String[] arguments) {
        if (arguments.length != 1 || arguments[0].isBlank()) {
            throw new IllegalArgumentException("Expected one non-blank probe span name");
        }

        return arguments[0];
    }

    private static CompositeData readinessSnapshot(MBeanServer server) throws Exception {
        Object raw = server.getAttribute(
                JmxWireContract.EXTENSION_READINESS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT
        );
        require(raw instanceof CompositeData, "ReadinessSnapshot must be CompositeData");

        CompositeData snapshot = (CompositeData) raw;
        require(JmxOpenTypes.READINESS_SNAPSHOT.equals(snapshot.getCompositeType()),
                "ReadinessSnapshot OpenType differs from the generated application contract");
        return snapshot;
    }

    private static String stringField(CompositeData snapshot, String field) {
        Object raw = snapshot.get(field);
        require(raw instanceof String, "Readiness field has an unexpected type: " + field);
        return (String) raw;
    }

    @SuppressWarnings("SameParameterValue")
    private static String[] stringArrayField(CompositeData snapshot, String field) {
        Object raw = snapshot.get(field);
        require(raw instanceof String[], "Readiness field has an unexpected type: " + field);
        return (String[]) raw;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
