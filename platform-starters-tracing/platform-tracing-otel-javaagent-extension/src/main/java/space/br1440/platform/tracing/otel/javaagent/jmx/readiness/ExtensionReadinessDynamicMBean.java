package space.br1440.platform.tracing.otel.javaagent.jmx.readiness;

import java.util.Objects;
import java.util.function.Supplier;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.ReflectionException;
import javax.management.openmbean.OpenMBeanAttributeInfo;
import javax.management.openmbean.OpenMBeanAttributeInfoSupport;
import javax.management.openmbean.OpenMBeanConstructorInfo;
import javax.management.openmbean.OpenMBeanInfoSupport;
import javax.management.openmbean.OpenMBeanOperationInfo;

import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;

public final class ExtensionReadinessDynamicMBean implements DynamicMBean {

    private static final String READINESS_ATTRIBUTE = JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT;
    private static final MBeanInfo INFO = buildMBeanInfo();

    private final Supplier<ExtensionReadinessWireSnapshot> projection;

    public ExtensionReadinessDynamicMBean(Supplier<ExtensionReadinessWireSnapshot> projection) {
        this.projection = Objects.requireNonNull(projection, "projection");
    }

    @Override
    public Object getAttribute(String attribute) throws AttributeNotFoundException {
        if (!READINESS_ATTRIBUTE.equals(attribute)) {
            throw new AttributeNotFoundException("Unknown extension readiness attribute: " + attribute);
        }

        return snapshot().readinessSnapshot();
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

        ExtensionReadinessWireSnapshot captured = snapshot();
        for (String attribute : attributes) {
            if (READINESS_ATTRIBUTE.equals(attribute)) {
                result.add(new Attribute(READINESS_ATTRIBUTE, captured.readinessSnapshot()));
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

    private static MBeanInfo buildMBeanInfo() {
        OpenMBeanAttributeInfo readiness = new OpenMBeanAttributeInfoSupport(
                READINESS_ATTRIBUTE,
                "Coherent platform extension readiness snapshot",
                JmxOpenTypes.READINESS_SNAPSHOT,
                true,
                false,
                false
        );

        return new OpenMBeanInfoSupport(
                ExtensionReadinessDynamicMBean.class.getName(),
                "Platform extension readiness generated from the JMX wire contract",
                new OpenMBeanAttributeInfo[] {readiness},
                new OpenMBeanConstructorInfo[0],
                new OpenMBeanOperationInfo[0],
                new MBeanNotificationInfo[0]
        );
    }
}
