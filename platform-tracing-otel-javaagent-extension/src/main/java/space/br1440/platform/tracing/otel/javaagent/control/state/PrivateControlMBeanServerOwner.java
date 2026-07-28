package space.br1440.platform.tracing.otel.javaagent.control.state;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import java.util.Objects;

/**
 * Exactly-one lifecycle owner factory-retained private MBeanServer.
 *
 * <p>P2 не подключает этот owner к bootstrap: поддерживаемый writer переключается только
 * в coherent cutover последующих фаз.
 */
final class PrivateControlMBeanServerOwner implements AutoCloseable {

    private final String runtimeInstanceId;
    private final PrivateControlMarker marker;
    private final PrivateControlWriter writer;

    private MBeanServer server;

    PrivateControlMBeanServerOwner(
            String runtimeInstanceId,
            AcceptedPolicyStateStore store,
            PolicyValidationConstraints constraints) {
        this.runtimeInstanceId = Objects.requireNonNull(runtimeInstanceId, "runtimeInstanceId");
        this.marker = new PrivateControlMarker(runtimeInstanceId);
        PolicyApplyDiagnostics diagnostics = new PolicyApplyDiagnostics();
        PolicyAuditBuffer audit = new PolicyAuditBuffer();
        AcceptedPolicyCoordinator coordinator = new AcceptedPolicyCoordinator(
                Objects.requireNonNull(store, "store"),
                Objects.requireNonNull(constraints, "constraints"),
                diagnostics,
                audit);
        this.writer = new PrivateControlWriter(
                coordinator,
                store,
                new PrivateControlOpenTypeCodec());
    }

    synchronized MBeanServer initialize() {
        if (server != null) {
            return server;
        }
        MBeanServer candidate = MBeanServerFactory.createMBeanServer(
                PrivateControlObjectNames.PRIVATE_DOMAIN);
        try {
            candidate.registerMBean(marker, PrivateControlObjectNames.MARKER);
            marker.lifecycleState("MARKER_REGISTERED");
            PrivateControlWireTypes.initialize();
            marker.lifecycleState("CODEC_READY");
            candidate.registerMBean(writer, PrivateControlObjectNames.WRITER);
            marker.lifecycleState("WRITER_READY");
            server = candidate;
            return candidate;
        } catch (Exception exception) {
            marker.lifecycleState("FAILED");
            cleanup(candidate);
            throw new IllegalStateException("Private control MBeanServer initialization failed", exception);
        }
    }

    String runtimeInstanceId() {
        return runtimeInstanceId;
    }

    @Override
    public synchronized void close() {
        MBeanServer current = server;
        if (current == null) {
            return;
        }
        cleanup(current);
        server = null;
        marker.lifecycleState("STOPPED");
    }

    private static void cleanup(MBeanServer current) {
        try {
            if (current.isRegistered(PrivateControlObjectNames.WRITER)) {
                current.unregisterMBean(PrivateControlObjectNames.WRITER);
            }
            if (current.isRegistered(PrivateControlObjectNames.MARKER)) {
                current.unregisterMBean(PrivateControlObjectNames.MARKER);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Private control MBean cleanup failed", exception);
        } finally {
            if (MBeanServerFactory.findMBeanServer(null).contains(current)) {
                MBeanServerFactory.releaseMBeanServer(current);
            }
        }
    }
}
