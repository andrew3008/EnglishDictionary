package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import java.util.Objects;
import java.util.function.Supplier;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.ReflectionException;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenMBeanAttributeInfo;
import javax.management.openmbean.OpenMBeanAttributeInfoSupport;
import javax.management.openmbean.OpenMBeanConstructorInfo;
import javax.management.openmbean.OpenMBeanInfoSupport;
import javax.management.openmbean.OpenMBeanOperationInfo;

final class ReadOnlyCompositeDataDynamicMBean implements DynamicMBean {

    private static final String ENDPOINT_CLASS_NAME = "space.br1440.platform.tracing.jmx.GeneratedReadOnlyEndpoint";

    private final String attributeName;
    private final Supplier<CompositeData> snapshotSupplier;
    private final MBeanInfo info;

    ReadOnlyCompositeDataDynamicMBean(String attributeName,
                                      CompositeType openType,
                                      Supplier<CompositeData> snapshotSupplier) {
        this.attributeName = Objects.requireNonNull(attributeName, "attributeName");
        Objects.requireNonNull(openType, "openType");
        this.snapshotSupplier = Objects.requireNonNull(snapshotSupplier, "snapshotSupplier");
        this.info = buildMBeanInfo(attributeName, openType);
    }

    @Override
    public Object getAttribute(String requestedAttribute) throws AttributeNotFoundException {
        if (!attributeName.equals(requestedAttribute)) {
            throw new AttributeNotFoundException("Unknown JMX observation attribute: " + requestedAttribute);
        }

        return snapshot();
    }

    @Override
    public void setAttribute(Attribute attribute) throws AttributeNotFoundException {
        throw new AttributeNotFoundException("JMX observation endpoint is read-only");
    }

    @Override
    public AttributeList getAttributes(String[] requestedAttributes) {
        AttributeList result = new AttributeList();
        if (requestedAttributes == null) {
            return result;
        }

        CompositeData captured = null;
        for (String requestedAttribute : requestedAttributes) {
            if (attributeName.equals(requestedAttribute)) {
                if (captured == null) {
                    captured = snapshot();
                }
                result.add(new Attribute(attributeName, captured));
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
        NoSuchMethodException rootCause = new NoSuchMethodException("JMX observation endpoint has no operations: " + actionName);
        throw new ReflectionException(rootCause);
    }

    @Override
    public MBeanInfo getMBeanInfo() {
        return info;
    }

    private CompositeData snapshot() {
        return Objects.requireNonNull(snapshotSupplier.get(), "snapshotSupplier.get()");
    }

    private static MBeanInfo buildMBeanInfo(String attributeName, CompositeType openType) {
        OpenMBeanAttributeInfo attribute = new OpenMBeanAttributeInfoSupport(
                attributeName,
                "Generated platform tracing read-only snapshot",
                openType,
                true,
                false,
                false
        );

        return new OpenMBeanInfoSupport(
                ENDPOINT_CLASS_NAME,
                "Generated platform tracing read-only endpoint",
                new OpenMBeanAttributeInfo[]{attribute},
                new OpenMBeanConstructorInfo[0],
                new OpenMBeanOperationInfo[0],
                new MBeanNotificationInfo[0]);
    }
}
