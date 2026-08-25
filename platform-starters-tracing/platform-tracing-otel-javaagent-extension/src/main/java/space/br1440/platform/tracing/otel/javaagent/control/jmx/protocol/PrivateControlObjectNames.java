package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class PrivateControlObjectNames {

    public static final int PROTOCOL_VERSION = 1;
    public static final String PROTOCOL_ID = "platform-tracing-private-control";
    public static final String MARKER_SIGNATURE = "PLATFORM_PRIVATE_CONTROL_V1";
    public static final String MARKER_OBJECT_NAME = "space.br1440.platform.tracing:type=PrivateMarker,name=PlatformPrivateControlPlane";
    public static final String WRITER_OBJECT_NAME = "space.br1440.platform.tracing:name=PlatformControlProtocol,type=Control";
    public static final String PRIVATE_DOMAIN = "space.br1440.platform.tracing.private";

    public static final ObjectName MARKER = objectName(MARKER_OBJECT_NAME);
    public static final ObjectName WRITER = objectName(WRITER_OBJECT_NAME);

    private static ObjectName objectName(String value) {
        try {
            return new ObjectName(value);
        } catch (MalformedObjectNameException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
