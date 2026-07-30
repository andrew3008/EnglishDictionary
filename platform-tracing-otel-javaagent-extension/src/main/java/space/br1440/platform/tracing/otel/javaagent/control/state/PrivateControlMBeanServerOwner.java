package space.br1440.platform.tracing.otel.javaagent.control.state;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import java.lang.management.ManagementFactory;
import java.util.Objects;

/**
 * Exactly-one lifecycle owner factory-retained private MBeanServer.
 *
 * <p>P6.2/P6.6: activated from Agent bootstrap via
 * {@link AcceptedPolicyAccess#activatePrivateControlPlane()}. Registers:
 * <ul>
 *   <li>private marker + {@code applySnapshot}/{@code readAcceptedComplete} writer</li>
 *   <li>E7 read-only platform Control projection under the same logical ObjectName
 *       on the platform MBeanServer (zero writable attributes / mutating operations)</li>
 * </ul>
 */
final class PrivateControlMBeanServerOwner implements AutoCloseable {

    private final String runtimeInstanceId;
    private final AcceptedPolicyStateStore store;
    private final PolicyApplyDiagnostics diagnostics;
    private final PrivateControlMarker marker;
    private final PrivateControlWriter writer;

    private MBeanServer server;
    private boolean platformProjectionRegistered;

    PrivateControlMBeanServerOwner(
            String runtimeInstanceId,
            AcceptedPolicyStateStore store,
            PolicyValidationConstraints constraints) {
        this.runtimeInstanceId = Objects.requireNonNull(runtimeInstanceId, "runtimeInstanceId");
        this.store = Objects.requireNonNull(store, "store");
        this.marker = new PrivateControlMarker(runtimeInstanceId);
        this.diagnostics = new PolicyApplyDiagnostics();
        PolicyAuditBuffer audit = new PolicyAuditBuffer();
        AcceptedPolicyCoordinator coordinator = new AcceptedPolicyCoordinator(
                store,
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
            registerPlatformReadProjection();
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

    private void registerPlatformReadProjection() throws Exception {
        MBeanServer platform = ManagementFactory.getPlatformMBeanServer();
        PlatformControlReadProjection projection = new PlatformControlReadProjection(
                store,
                diagnostics,
                () -> false,
                () -> false);
        if (platform.isRegistered(PrivateControlObjectNames.WRITER)) {
            platform.unregisterMBean(PrivateControlObjectNames.WRITER);
        }
        platform.registerMBean(projection, PrivateControlObjectNames.WRITER);
        platformProjectionRegistered = true;
    }

    private void cleanup(MBeanServer current) {
        try {
            if (platformProjectionRegistered) {
                MBeanServer platform = ManagementFactory.getPlatformMBeanServer();
                if (platform.isRegistered(PrivateControlObjectNames.WRITER)) {
                    platform.unregisterMBean(PrivateControlObjectNames.WRITER);
                }
                platformProjectionRegistered = false;
            }
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
