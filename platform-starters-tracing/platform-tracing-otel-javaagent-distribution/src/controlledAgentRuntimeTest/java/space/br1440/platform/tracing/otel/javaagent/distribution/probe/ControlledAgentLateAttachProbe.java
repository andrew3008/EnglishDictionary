package space.br1440.platform.tracing.otel.javaagent.distribution.probe;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Locale;
import javax.management.MBeanServer;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

public final class ControlledAgentLateAttachProbe {

    public static final String READY_LINE_PREFIX = "CONTROLLED_AGENT_LATE_ATTACH_PROBE_READY pid=";

    private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration READINESS_POLL_INTERVAL = Duration.ofMillis(50);
    private static final String LIFECYCLE_READY = "READY";
    private static final String LIFECYCLE_FAILED = "FAILED";

    private ControlledAgentLateAttachProbe() {
    }

    public static void main(String[] arguments) throws Exception {
        awaitExtensionReady(ManagementFactory.getPlatformMBeanServer());
        announceReady();
        awaitParentShutdownSignal();
    }

    private static void announceReady() {
        System.out.printf(Locale.ROOT, "%s%d%n", READY_LINE_PREFIX, ProcessHandle.current().pid());
        System.out.flush();
    }

    private static void awaitParentShutdownSignal() throws IOException {
        System.in.transferTo(OutputStream.nullOutputStream());
    }

    private static void awaitExtensionReady(MBeanServer server) throws Exception {
        MonotonicDeadline deadline = MonotonicDeadline.after(READINESS_TIMEOUT);
        while (deadline.hasRemaining()) {
            if (!server.isRegistered(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME)) {
                sleepPollInterval();
                continue;
            }

            CompositeData snapshot = readinessSnapshot(server);
            String lifecycle = stringField(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE);

            if (LIFECYCLE_READY.equals(lifecycle)) {
                return;
            }

            if (LIFECYCLE_FAILED.equals(lifecycle)) {
                throw new IllegalStateException("Platform extension readiness failed: code=%s".formatted(
                        stringField(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_FAILURE_CODE)));
            }

            sleepPollInterval();
        }

        throw new IllegalStateException(
                "Platform extension readiness did not become READY within %d seconds"
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
