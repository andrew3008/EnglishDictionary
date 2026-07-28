package space.br1440.platform.tracing.otel.javaagent.control.state;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.DynamicMBean;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.ReflectionException;

/**
 * Read-only marker для строгого обнаружения private MBeanServer.
 */
final class PrivateControlMarker implements DynamicMBean {

    private static final MBeanAttributeInfo[] ATTRIBUTES = {
            attribute("ProtocolId", String.class),
            attribute("ProtocolVersion", Integer.TYPE),
            attribute("RuntimeInstanceId", String.class),
            attribute("WriterObjectName", String.class),
            attribute("LifecycleState", String.class),
            attribute("MarkerSignature", String.class)
    };
    private static final MBeanInfo INFO = new MBeanInfo(
            PrivateControlMarker.class.getName(),
            "Platform private control-plane marker",
            ATTRIBUTES,
            null,
            null,
            null);

    private final String runtimeInstanceId;
    private volatile String lifecycleState = "CREATED";

    PrivateControlMarker(String runtimeInstanceId) {
        this.runtimeInstanceId = runtimeInstanceId;
    }

    void lifecycleState(String value) {
        lifecycleState = value;
    }

    @Override
    public Object getAttribute(String attribute) {
        return switch (attribute) {
            case "ProtocolId" -> PrivateControlObjectNames.PROTOCOL_ID;
            case "ProtocolVersion" -> PrivateControlObjectNames.PROTOCOL_VERSION;
            case "RuntimeInstanceId" -> runtimeInstanceId;
            case "WriterObjectName" -> PrivateControlObjectNames.WRITER_OBJECT_NAME;
            case "LifecycleState" -> lifecycleState;
            case "MarkerSignature" -> PrivateControlObjectNames.MARKER_SIGNATURE;
            default -> throw new IllegalArgumentException("Unknown marker attribute: " + attribute);
        };
    }

    @Override
    public void setAttribute(Attribute attribute) {
        throw new UnsupportedOperationException("Private control marker is read-only");
    }

    @Override
    public AttributeList getAttributes(String[] attributes) {
        AttributeList result = new AttributeList();
        for (String attribute : attributes) {
            result.add(new Attribute(attribute, getAttribute(attribute)));
        }
        return result;
    }

    @Override
    public AttributeList setAttributes(AttributeList attributes) {
        return new AttributeList();
    }

    @Override
    public Object invoke(String actionName, Object[] params, String[] signature)
            throws ReflectionException {
        throw new ReflectionException(new NoSuchMethodException(actionName));
    }

    @Override
    public MBeanInfo getMBeanInfo() {
        return INFO;
    }

    private static MBeanAttributeInfo attribute(String name, Class<?> type) {
        return new MBeanAttributeInfo(name, type.getName(), name, true, false, false);
    }
}
