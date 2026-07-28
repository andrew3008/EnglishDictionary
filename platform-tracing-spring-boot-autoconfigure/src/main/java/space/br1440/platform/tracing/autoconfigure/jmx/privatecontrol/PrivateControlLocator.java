package space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol;

import java.util.ArrayList;
import java.util.List;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;

/**
 * Строгий application-side locator process-local control plane.
 *
 * <p>Internal delivery seam for Config Server / DEV adapters. Not a supported application API.
 */
public final class PrivateControlLocator {

    public static final String PROTOCOL_ID = "platform-tracing-private-control";
    public static final int PROTOCOL_VERSION = 1;
    public static final String MARKER_SIGNATURE = "PLATFORM_PRIVATE_CONTROL_V1";
    public static final String MARKER_NAME =
            "space.br1440.platform.tracing:type=PrivateMarker,name=PlatformPrivateControlPlane";
    public static final String WRITER_NAME =
            "space.br1440.platform.tracing:name=PlatformControlProtocol,type=Control";

    public LocatedPrivateControl locate() {
        ObjectName marker = objectName(MARKER_NAME);
        ObjectName writer = objectName(WRITER_NAME);
        List<LocatedPrivateControl> matches = new ArrayList<>();
        int markerCount = 0;
        for (MBeanServer server : MBeanServerFactory.findMBeanServer(null)) {
            if (!server.isRegistered(marker)) {
                continue;
            }
            markerCount++;
            try {
                String protocol = (String) server.getAttribute(marker, "ProtocolId");
                int version = (Integer) server.getAttribute(marker, "ProtocolVersion");
                String runtimeId = (String) server.getAttribute(marker, "RuntimeInstanceId");
                String writerName = (String) server.getAttribute(marker, "WriterObjectName");
                String lifecycle = (String) server.getAttribute(marker, "LifecycleState");
                String signature = (String) server.getAttribute(marker, "MarkerSignature");
                if (PROTOCOL_ID.equals(protocol)
                        && version == PROTOCOL_VERSION
                        && runtimeId != null
                        && !runtimeId.isBlank()
                        && WRITER_NAME.equals(writerName)
                        && "WRITER_READY".equals(lifecycle)
                        && MARKER_SIGNATURE.equals(signature)
                        && server.isRegistered(writer)) {
                    matches.add(new LocatedPrivateControl(server, writer, runtimeId));
                }
            } catch (Exception ignored) {
                // Несовместимый marker проверяется после полного exactly-one поиска.
            }
        }
        if (markerCount == 0) {
            throw new PrivateControlUnavailableException();
        }
        if (markerCount != 1 || matches.size() != 1) {
            throw new IllegalStateException(
                    "Private control plane marker set violates the exactly-one compatible contract");
        }
        return matches.get(0);
    }

    private static ObjectName objectName(String value) {
        try {
            return new ObjectName(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid private control ObjectName", exception);
        }
    }

    public record LocatedPrivateControl(
            MBeanServer server,
            ObjectName writerObjectName,
            String runtimeInstanceId) {
    }

    /**
     * Ожидаемое отсутствие private control plane до готовности Controlled Agent.
     */
    public static final class PrivateControlUnavailableException extends RuntimeException {

        public PrivateControlUnavailableException() {
            super("Compatible private control plane is unavailable");
        }
    }
}
