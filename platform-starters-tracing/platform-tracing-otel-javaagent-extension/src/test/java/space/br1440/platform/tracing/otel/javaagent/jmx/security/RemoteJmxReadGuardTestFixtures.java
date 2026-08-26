package space.br1440.platform.tracing.otel.javaagent.jmx.security;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.openmbean.ArrayType;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenMBeanAttributeInfo;
import javax.management.openmbean.OpenMBeanAttributeInfoSupport;
import javax.management.openmbean.OpenMBeanConstructorInfo;
import javax.management.openmbean.OpenMBeanInfoSupport;
import javax.management.openmbean.OpenMBeanOperationInfo;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;

final class RemoteJmxReadGuardTestFixtures {

    private RemoteJmxReadGuardTestFixtures() {
    }

    static DynamicMBean endpoint(
            String attributeName,
            CompositeType openType,
            boolean openMetadata,
            Object payload) {
        return new ContractDynamicMBean(attributeName, openType, openMetadata, payload);
    }

    static CompositeData neutralComposite(CompositeType type) {
        return composite(type, Map.of());
    }

    static CompositeData composite(CompositeType type, Map<String, ?> overrides) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String itemName : type.keySet()) {
            values.put(itemName, neutralValue(type.getType(itemName)));
        }
        values.putAll(overrides);
        try {
            return new CompositeDataSupport(type, values);
        } catch (OpenDataException failure) {
            throw new IllegalArgumentException("Cannot create generated test payload", failure);
        }
    }

    private static Object neutralValue(OpenType<?> type) {
        if (type instanceof CompositeType compositeType) {
            return neutralComposite(compositeType);
        }
        if (type instanceof TabularType tabularType) {
            return new TabularDataSupport(tabularType);
        }
        if (type instanceof ArrayType<?> arrayType) {
            return emptyArray(arrayType);
        }
        if (SimpleType.STRING.equals(type)) {
            return "";
        }
        if (SimpleType.BOOLEAN.equals(type)) {
            return false;
        }
        if (SimpleType.BYTE.equals(type)) {
            return (byte) 0;
        }
        if (SimpleType.SHORT.equals(type)) {
            return (short) 0;
        }
        if (SimpleType.INTEGER.equals(type)) {
            return 0;
        }
        if (SimpleType.LONG.equals(type)) {
            return 0L;
        }
        if (SimpleType.FLOAT.equals(type)) {
            return 0.0f;
        }
        if (SimpleType.DOUBLE.equals(type)) {
            return 0.0d;
        }
        if (SimpleType.CHARACTER.equals(type)) {
            return '\0';
        }
        if (SimpleType.OBJECTNAME.equals(type)) {
            return objectName("space.br1440.platform.tracing.test:type=Neutral");
        }
        throw new IllegalArgumentException("Unsupported generated test OpenType");
    }

    private static Object emptyArray(ArrayType<?> type) {
        if (type.getDimension() != 1) {
            throw new IllegalArgumentException("Unsupported generated test array dimension");
        }
        OpenType<?> elementType = type.getElementOpenType();
        if (SimpleType.STRING.equals(elementType)) {
            return new String[0];
        }
        if (SimpleType.BOOLEAN.equals(elementType)) {
            return new boolean[0];
        }
        if (SimpleType.BYTE.equals(elementType)) {
            return new byte[0];
        }
        if (SimpleType.SHORT.equals(elementType)) {
            return new short[0];
        }
        if (SimpleType.INTEGER.equals(elementType)) {
            return new int[0];
        }
        if (SimpleType.LONG.equals(elementType)) {
            return new long[0];
        }
        if (SimpleType.FLOAT.equals(elementType)) {
            return new float[0];
        }
        if (SimpleType.DOUBLE.equals(elementType)) {
            return new double[0];
        }
        if (SimpleType.CHARACTER.equals(elementType)) {
            return new char[0];
        }
        throw new IllegalArgumentException("Unsupported generated test array type");
    }

    private static ObjectName objectName(String value) {
        try {
            return new ObjectName(value);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid test ObjectName", failure);
        }
    }

    private static final class ContractDynamicMBean implements DynamicMBean {

        private final String attributeName;
        private final Object payload;
        private final MBeanInfo info;

        private ContractDynamicMBean(
                String attributeName,
                CompositeType openType,
                boolean openMetadata,
                Object payload) {
            this.attributeName = attributeName;
            this.payload = payload;
            this.info = openMetadata
                    ? openInfo(attributeName, openType)
                    : standardInfo(attributeName);
        }

        @Override
        public Object getAttribute(String requestedAttribute) throws AttributeNotFoundException {
            if (!attributeName.equals(requestedAttribute)) {
                throw new AttributeNotFoundException("Unknown test attribute");
            }
            return payload;
        }

        @Override
        public void setAttribute(Attribute attribute) throws AttributeNotFoundException {
            throw new AttributeNotFoundException("Test endpoint is read-only");
        }

        @Override
        public AttributeList getAttributes(String[] attributes) {
            AttributeList result = new AttributeList();
            if (attributes != null) {
                for (String requestedAttribute : attributes) {
                    if (attributeName.equals(requestedAttribute)) {
                        result.add(new Attribute(attributeName, payload));
                    }
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
            throw new ReflectionException(new NoSuchMethodException("Test endpoint has no operations"));
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            return info;
        }

        private static MBeanInfo standardInfo(String attributeName) {
            MBeanAttributeInfo attribute = new MBeanAttributeInfo(
                    attributeName,
                    CompositeData.class.getName(),
                    "Test generated snapshot",
                    true,
                    false,
                    false);
            return new MBeanInfo(
                    ContractDynamicMBean.class.getName(),
                    "Test generated endpoint",
                    new MBeanAttributeInfo[]{attribute},
                    null,
                    null,
                    null);
        }

        private static MBeanInfo openInfo(String attributeName, CompositeType openType) {
            OpenMBeanAttributeInfo attribute = new OpenMBeanAttributeInfoSupport(
                    attributeName,
                    "Test generated snapshot",
                    openType,
                    true,
                    false,
                    false);
            return new OpenMBeanInfoSupport(
                    ContractDynamicMBean.class.getName(),
                    "Test generated endpoint",
                    new OpenMBeanAttributeInfo[]{attribute},
                    new OpenMBeanConstructorInfo[0],
                    new OpenMBeanOperationInfo[0],
                    new MBeanNotificationInfo[0]);
        }
    }
}
