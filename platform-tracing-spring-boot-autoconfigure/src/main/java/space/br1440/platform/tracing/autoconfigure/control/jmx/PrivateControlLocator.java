package space.br1440.platform.tracing.autoconfigure.control.jmx;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.management.MBeanServer;
import javax.management.ObjectName;

public final class PrivateControlLocator {

    public static final int PROTOCOL_VERSION = 1;

    static final String PROTOCOL_ID = "platform-tracing-private-control";
    static final String MARKER_SIGNATURE = "PLATFORM_PRIVATE_CONTROL_V1";
    static final String LIFECYCLE_WRITER_READY = "WRITER_READY";
    static final String MARKER_NAME = "space.br1440.platform.tracing:type=PrivateMarker,name=PlatformPrivateControlPlane";
    static final String WRITER_NAME = "space.br1440.platform.tracing:name=PlatformControlProtocol,type=Control";

    private static final ObjectName MARKER_OBJECT_NAME = objectName(MARKER_NAME);
    private static final ObjectName WRITER_OBJECT_NAME = objectName(WRITER_NAME);

    private final MBeanServerSource serverSource;
    private final PrivateControlMarkerInspector inspector;

    public PrivateControlLocator() {
        this(MBeanServerSource.factoryRetained(), new PrivateControlMarkerInspector());
    }

    PrivateControlLocator(MBeanServerSource serverSource, PrivateControlMarkerInspector inspector) {
        this.serverSource = serverSource;
        this.inspector = inspector;
    }

    LocatedPrivateControl locate() {
        List<MBeanServer> servers;
        try {
            servers = serverSource.snapshot();
        } catch (SecurityException exception) {
            throw new IllegalStateException(
                    "Unable to enumerate factory-retained MBean servers for private control discovery",
                    exception
            );
        }

        DiscoveryDiagnostics diagnostics = scan(servers);
        if (diagnostics.compatibleCount() == 1) {
            return diagnostics.found();
        }

        throw new IllegalStateException(diagnostics.toExceptionMessage());
    }

    private DiscoveryDiagnostics scan(List<MBeanServer> servers) {
        LocatedPrivateControl found = null;
        int compatibleCount = 0;
        int markersFound = 0;
        Map<PrivateControlMarkerInspector.ProbeReason, Integer> tallies = new EnumMap<>(PrivateControlMarkerInspector.ProbeReason.class);

        for (MBeanServer server : servers) {
            PrivateControlMarkerInspector.ProbeResult result = inspector.inspect(server, MARKER_OBJECT_NAME, WRITER_OBJECT_NAME);

            if (result instanceof PrivateControlMarkerInspector.ProbeResult.Indeterminate(
                    PrivateControlMarkerInspector.ProbeReason reason, Throwable cause
            )) {
                String message = "Private control discovery aborted: " + reason + " while inspecting a candidate marker";
                throw new IllegalStateException(message, cause);
            }

            if (result instanceof PrivateControlMarkerInspector.ProbeResult.Absent) {
                continue;
            }

            markersFound++;

            if (result instanceof PrivateControlMarkerInspector.ProbeResult.Compatible(LocatedPrivateControl control)) {
                compatibleCount++;

                if (found == null) {
                    found = control;
                }
            } else if (result instanceof PrivateControlMarkerInspector.ProbeResult.Rejected(
                    PrivateControlMarkerInspector.ProbeReason reason
            )) {
                tallies.merge(reason, 1, Integer::sum);
            }
        }

        return new DiscoveryDiagnostics(servers.size(), markersFound, compatibleCount, found, tallies);
    }

    private static ObjectName objectName(String value) {
        try {
            return new ObjectName(value);
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    record LocatedPrivateControl(MBeanServer server, ObjectName writerObjectName, String runtimeInstanceId) {
    }

    private record DiscoveryDiagnostics(int serversScanned,
                                        int markersFound,
                                        int compatibleCount,
                                        LocatedPrivateControl found,
                                        Map<PrivateControlMarkerInspector.ProbeReason, Integer> reasonTallies) {

        String toExceptionMessage() {
            StringBuilder message = new StringBuilder("Expected exactly one compatible private control plane, found " + compatibleCount);
            if (markersFound > 0 && compatibleCount == 0) {
                message.append(" (");

                boolean first = true;
                for (PrivateControlMarkerInspector.ProbeReason reason : PrivateControlMarkerInspector.ProbeReason.values()) {
                    Integer count = reasonTallies.get(reason);
                    if (count == null) {
                        continue;
                    }

                    if (!first) {
                        message.append(", ");
                    }

                    message.append(reason).append('=').append(count);
                    first = false;
                }

                message.append(')');
            }

            return message.toString();
        }
    }
}
