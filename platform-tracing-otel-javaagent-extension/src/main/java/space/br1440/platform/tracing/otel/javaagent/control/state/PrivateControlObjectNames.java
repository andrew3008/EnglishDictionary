package space.br1440.platform.tracing.otel.javaagent.control.state;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

/**
 * Внутренние идентификаторы process-local control plane.
 */
final class PrivateControlObjectNames {

    static final int PROTOCOL_VERSION = 1;
    static final String PROTOCOL_ID = "platform-tracing-private-control";
    static final String MARKER_SIGNATURE = "PLATFORM_PRIVATE_CONTROL_V1";
    static final String MARKER_OBJECT_NAME =
            "space.br1440.platform.tracing:type=PrivateMarker,name=PlatformPrivateControlPlane";
    static final String WRITER_OBJECT_NAME =
            "space.br1440.platform.tracing:name=PlatformControlProtocol,type=Control";
    static final String PRIVATE_DOMAIN = "space.br1440.platform.tracing.private";

    static final ObjectName MARKER = objectName(MARKER_OBJECT_NAME);
    static final ObjectName WRITER = objectName(WRITER_OBJECT_NAME);

    private PrivateControlObjectNames() {
    }

    private static ObjectName objectName(String value) {
        try {
            return new ObjectName(value);
        } catch (MalformedObjectNameException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
