package space.br1440.platform.tracing.otel.javaagent.distribution.probe;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Locale;
import javax.management.MBeanServer;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

public final class ControlledAgentRemoteJmxDenialProbe {

    public static final String OK_PREFIX = "CONTROLLED_AGENT_REMOTE_JMX_DENIED_OK";
    public static final String EXPECTED_FAILURE_CODE = "REMOTE_JMX_MANAGEMENT_AGENT_CONFIGURED";

    private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration READINESS_POLL_INTERVAL = Duration.ofMillis(50);
    private static final String LIFECYCLE_READY = "READY";
    private static final String LIFECYCLE_FAILED = "FAILED";

    private ControlledAgentRemoteJmxDenialProbe() {
    }

    public static void main(String[] arguments) throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        CompositeData snapshot = waitForFailedSnapshot(server);

        String failureCode = stringField(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_FAILURE_CODE);
        require(EXPECTED_FAILURE_CODE.equals(failureCode), "Unexpected remote JMX denial failureCode: " + failureCode);

        boolean mutationRegistered = server.isRegistered(JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME);
        require(!mutationRegistered, "Mutation ObjectName must stay unregistered after remote JMX denial");

        boolean readinessRegistered = server.isRegistered(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME);
        require(readinessRegistered, "Diagnostic readiness ObjectName must remain available after remote JMX denial");

        System.out.printf(
                Locale.ROOT,
                "%s failureCode=%s mutationRegistered=%s readinessRegistered=%s%n",
                OK_PREFIX,
                failureCode,
                mutationRegistered,
                readinessRegistered
        );
    }

    private static CompositeData waitForFailedSnapshot(MBeanServer server) throws Exception {
        MonotonicDeadline deadline = MonotonicDeadline.after(READINESS_TIMEOUT);
        while (deadline.hasRemaining()) {
            if (!server.isRegistered(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME)) {
                sleepPollInterval();
                continue;
            }

            CompositeData snapshot = readinessSnapshot(server);
            String lifecycle = stringField(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE);

            if (LIFECYCLE_READY.equals(lifecycle)) {
                throw new IllegalStateException("Remote JMX denial probe observed READY instead of FAILED");
            }

            if (LIFECYCLE_FAILED.equals(lifecycle)) {
                return snapshot;
            }

            sleepPollInterval();
        }

        throw new IllegalStateException(
                "Platform extension readiness did not become FAILED within %d seconds"
                        .formatted(READINESS_TIMEOUT.toSeconds())
        );
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

    private static void sleepPollInterval() throws InterruptedException {
        Thread.sleep(READINESS_POLL_INTERVAL.toMillis());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
