package space.br1440.platform.tracing.otel.javaagent.jmx.readiness;

import java.util.Objects;
import java.util.function.Supplier;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.ReflectionException;

public final class ExtensionReadinessDynamicMBean implements DynamicMBean {

    private static final MBeanInfo INFO = buildMBeanInfo();

    private final Supplier<ExtensionReadinessWireSnapshot> projection;

    public ExtensionReadinessDynamicMBean(Supplier<ExtensionReadinessWireSnapshot> projection) {
        this.projection = Objects.requireNonNull(projection, "projection");
    }

    @Override
    public Object getAttribute(String attribute) throws AttributeNotFoundException {
        return attributeValue(attribute, snapshot());
    }

    @Override
    public void setAttribute(Attribute attribute) throws MBeanException {
        throw new MBeanException(new UnsupportedOperationException("Extension readiness is read-only"));
    }

    @Override
    public AttributeList getAttributes(String[] attributes) {
        AttributeList result = new AttributeList();
        if (attributes == null) {
            return result;
        }

        ExtensionReadinessWireSnapshot snap = snapshot();
        for (String attribute : attributes) {
            try {
                result.add(new Attribute(attribute, attributeValue(attribute, snap)));
            } catch (AttributeNotFoundException ignored) {
            }
        }

        return result;
    }

    @Override
    public AttributeList setAttributes(AttributeList attributes) {
        return new AttributeList();
    }

    @Override
    public Object invoke(String actionName, Object[] params, String[] signature) throws ReflectionException {
        throw new ReflectionException(new NoSuchMethodException(actionName));
    }

    @Override
    public MBeanInfo getMBeanInfo() {
        return INFO;
    }

    private ExtensionReadinessWireSnapshot snapshot() {
        return Objects.requireNonNull(projection.get(), "projection.get()");
    }

    private static Object attributeValue(String attribute, ExtensionReadinessWireSnapshot snap) throws AttributeNotFoundException {
        return switch (attribute) {
            case "ExtensionVersion" -> snap.extensionVersion();
            case "ProtocolVersion" -> snap.protocolVersion();
            case "Profile" -> snap.profile();
            case "LifecycleState" -> snap.lifecycle();
            case "FailureCode" -> snap.failureCode();
            case "FailureMessage" -> snap.failureMessage();
            case "Capabilities" -> snap.capabilities();
            default -> throw new AttributeNotFoundException("Unknown Extension Readiness attribute: " + attribute);
        };
    }

    private static MBeanInfo buildMBeanInfo() {
        MBeanAttributeInfo[] attributes = {
                attribute("ExtensionVersion", String.class),
                attribute("ProtocolVersion", int.class),
                attribute("Profile", String.class),
                attribute("LifecycleState", String.class),
                attribute("FailureCode", String.class),
                attribute("FailureMessage", String.class),
                attribute("Capabilities", String[].class)
        };

        return new MBeanInfo(
                ExtensionReadinessDynamicMBean.class.getName(),
                "Platform extension readiness (protocol v1, snapshot-backed)",
                attributes,
                null,
                null,
                null
        );
    }

    private static MBeanAttributeInfo attribute(String name, Class<?> type) {
        return new MBeanAttributeInfo(name, type.getName(), name, true, false, false);
    }
}
