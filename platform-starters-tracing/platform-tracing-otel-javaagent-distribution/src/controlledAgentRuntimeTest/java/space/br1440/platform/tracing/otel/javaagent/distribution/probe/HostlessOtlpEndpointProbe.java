package space.br1440.platform.tracing.otel.javaagent.distribution.probe;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

public final class HostlessOtlpEndpointProbe {

    public static final String MAIN_REACHED_MARKER = "APPLICATION_MAIN_REACHED";
    public static final String RESULT_PREFIX = "HOSTLESS_OTLP_ENDPOINT_PROBE";

    private static final ObjectName[] RUNTIME_OBJECT_NAMES = {
            JmxWireContract.SAMPLING_OBSERVATION_OBJECT_NAME,
            JmxWireContract.SCRUBBING_OBSERVATION_OBJECT_NAME,
            JmxWireContract.VALIDATION_OBSERVATION_OBJECT_NAME,
            JmxWireContract.EXPORT_OBSERVATION_OBJECT_NAME,
            JmxWireContract.PROCESSOR_METRICS_OBSERVATION_OBJECT_NAME,
            JmxWireContract.DIAGNOSTICS_OBSERVATION_OBJECT_NAME,
            JmxWireContract.SETTINGS_STATUS_OBJECT_NAME,
            JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME
    };

    private HostlessOtlpEndpointProbe() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 0) {
            throw new IllegalArgumentException("Hostless OTLP probe does not accept arguments");
        }

        System.out.println(MAIN_REACHED_MARKER);

        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        CompositeData snapshot = readinessSnapshot(server);
        String lifecycle = stringField(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE);
        String runtimeMBeans = runtimeMBeansAbsent(server) ? "absent" : "present";
        List<String> platformThreads = Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.startsWith("platform-tracing-"))
                .sorted()
                .toList();

        System.out.printf(
                Locale.ROOT,
                "%s lifecycle=%s runtimeMBeans=%s platformThreads=%s%n",
                RESULT_PREFIX,
                lifecycle,
                runtimeMBeans,
                platformThreads
        );
    }

    private static boolean runtimeMBeansAbsent(MBeanServer server) {
        for (ObjectName objectName : RUNTIME_OBJECT_NAMES) {
            if (server.isRegistered(objectName)) {
                return false;
            }
        }
        return true;
    }

    private static CompositeData readinessSnapshot(MBeanServer server) throws Exception {
        Object raw = server.getAttribute(
                JmxWireContract.EXTENSION_READINESS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT
        );
        require(raw instanceof CompositeData, "ReadinessSnapshot must be CompositeData");

        CompositeData snapshot = (CompositeData) raw;
        require(
                JmxOpenTypes.READINESS_SNAPSHOT.equals(snapshot.getCompositeType()),
                "ReadinessSnapshot OpenType differs from the generated application contract"
        );
        return snapshot;
    }

    @SuppressWarnings("SameParameterValue")
    private static String stringField(CompositeData snapshot, String field) {
        Object raw = snapshot.get(field);
        require(raw instanceof String, "Readiness field has an unexpected type: " + field);
        return (String) raw;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
