package space.br1440.platform.tracing.otel.javaagent.jmx.security;

import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.ObjectName;
import javax.management.openmbean.ArrayType;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenMBeanAttributeInfo;
import javax.management.openmbean.OpenMBeanAttributeInfoSupport;
import javax.management.openmbean.OpenMBeanConstructorInfo;
import javax.management.openmbean.OpenMBeanInfoSupport;
import javax.management.openmbean.OpenMBeanOperationInfo;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;

import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxWireContract;

/**
 * Проверяет metadata и payload разрешённых remote read endpoints до RMI-сериализации.
 */
final class RemoteJmxReadGuard {

    private static final String CANONICAL_ENDPOINT_CLASS =
            "space.br1440.platform.tracing.jmx.GeneratedReadOnlyEndpoint";
    private static final String CONTRACT_VIOLATION =
            "Remote JMX value violates the generated read-only contract";
    private static final Set<String> ALLOWED_NEUTRAL_TYPES =
            Set.copyOf(GeneratedJmxWireContract.ALLOWED_NEUTRAL_TYPES);
    private static final Set<String> ALLOWED_ARRAY_COMPONENTS =
            Set.copyOf(GeneratedJmxWireContract.ALLOWED_ARRAY_COMPONENTS);

    private static final Map<ObjectName, Endpoint> ENDPOINTS = Map.ofEntries(
            endpoint(
                    GeneratedJmxWireContract.EXTENSION_READINESS_OBJECT_NAME,
                    GeneratedJmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT,
                    GeneratedJmxOpenTypes.READINESS_SNAPSHOT),
            endpoint(
                    GeneratedJmxWireContract.SETTINGS_STATUS_OBJECT_NAME,
                    GeneratedJmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT,
                    GeneratedJmxOpenTypes.ACCEPTED_POLICY_SNAPSHOT),
            endpoint(
                    GeneratedJmxWireContract.SAMPLING_OBSERVATION_OBJECT_NAME,
                    GeneratedJmxWireContract.ATTRIBUTE_SAMPLING_OBSERVATION_SAMPLING_OBSERVATION,
                    GeneratedJmxOpenTypes.SAMPLING_OBSERVATION),
            endpoint(
                    GeneratedJmxWireContract.SCRUBBING_OBSERVATION_OBJECT_NAME,
                    GeneratedJmxWireContract.ATTRIBUTE_SCRUBBING_OBSERVATION_SCRUBBING_OBSERVATION,
                    GeneratedJmxOpenTypes.SCRUBBING_OBSERVATION),
            endpoint(
                    GeneratedJmxWireContract.VALIDATION_OBSERVATION_OBJECT_NAME,
                    GeneratedJmxWireContract.ATTRIBUTE_VALIDATION_OBSERVATION_VALIDATION_OBSERVATION,
                    GeneratedJmxOpenTypes.VALIDATION_OBSERVATION),
            endpoint(
                    GeneratedJmxWireContract.EXPORT_OBSERVATION_OBJECT_NAME,
                    GeneratedJmxWireContract.ATTRIBUTE_EXPORT_OBSERVATION_EXPORT_OBSERVATION,
                    GeneratedJmxOpenTypes.EXPORT_OBSERVATION),
            endpoint(
                    GeneratedJmxWireContract.PROCESSOR_METRICS_OBSERVATION_OBJECT_NAME,
                    GeneratedJmxWireContract
                            .ATTRIBUTE_PROCESSOR_METRICS_OBSERVATION_PROCESSOR_METRICS_OBSERVATION,
                    GeneratedJmxOpenTypes.PROCESSOR_METRICS_OBSERVATION),
            endpoint(
                    GeneratedJmxWireContract.DIAGNOSTICS_OBSERVATION_OBJECT_NAME,
                    GeneratedJmxWireContract.ATTRIBUTE_DIAGNOSTICS_OBSERVATION_DIAGNOSTICS_OBSERVATION,
                    GeneratedJmxOpenTypes.DIAGNOSTICS_OBSERVATION));

    private RemoteJmxReadGuard() {
    }

    static Endpoint endpoint(ObjectName name) {
        return ENDPOINTS.get(name);
    }

    static boolean isReadEndpoint(ObjectName name) {
        return endpoint(name) != null;
    }

    static boolean matchesAttribute(ObjectName name, String attribute) {
        Endpoint endpoint = endpoint(name);
        return endpoint != null && endpoint.attributeName.equals(attribute);
    }

    private static Map.Entry<ObjectName, Endpoint> endpoint(
            ObjectName objectName,
            String attributeName,
            CompositeType openType) {
        return Map.entry(
                objectName,
                new Endpoint(attributeName, openType));
    }

    static final class Endpoint {

        private final String attributeName;
        private final CompositeType openType;
        private final MBeanInfo canonicalInfo;

        private Endpoint(String attributeName, CompositeType openType) {
            this.attributeName = attributeName;
            this.openType = openType;
            this.canonicalInfo = canonicalInfo(attributeName, openType);
        }

        String attributeName() {
            return attributeName;
        }

        MBeanInfo canonicalInfo() {
            return canonicalInfo;
        }

        void validateMetadata(MBeanInfo actual) {
            try {
                require(actual != null && actual.getClass() == OpenMBeanInfoSupport.class);
                requireEmpty(actual.getConstructors());
                requireEmpty(actual.getOperations());
                requireEmpty(actual.getNotifications());

                MBeanAttributeInfo[] attributes = actual.getAttributes();
                require(attributes != null && attributes.length == 1);
                MBeanAttributeInfo attribute = attributes[0];
                require(attribute != null);
                require(attributeName.equals(attribute.getName()));
                require(CompositeData.class.getName().equals(attribute.getType()));
                require(attribute.isReadable() && !attribute.isWritable() && !attribute.isIs());

                require(attribute.getClass() == OpenMBeanAttributeInfoSupport.class);
                OpenMBeanAttributeInfo openAttribute = (OpenMBeanAttributeInfo) attribute;
                require(!openAttribute.hasDefaultValue());
                require(!openAttribute.hasLegalValues());
                require(!openAttribute.hasMinValue());
                require(!openAttribute.hasMaxValue());
                validateOpenType(openAttribute.getOpenType(), openType);
            } catch (IllegalArgumentException contractViolation) {
                throw contractViolation;
            } catch (RuntimeException malformedMetadata) {
                throw violation();
            }
        }

        void validatePayload(Object payload) {
            new Traversal().visit(payload, openType, 0);
        }

        private static MBeanInfo canonicalInfo(String attributeName, CompositeType openType) {
            OpenMBeanAttributeInfo attribute = new OpenMBeanAttributeInfoSupport(
                    attributeName,
                    "Generated platform tracing read-only snapshot",
                    openType,
                    true,
                    false,
                    false);
            return new OpenMBeanInfoSupport(
                    CANONICAL_ENDPOINT_CLASS,
                    "Generated platform tracing read-only endpoint",
                    new OpenMBeanAttributeInfo[]{attribute},
                    new OpenMBeanConstructorInfo[0],
                    new OpenMBeanOperationInfo[0],
                    new MBeanNotificationInfo[0]);
        }
    }

    private static final class Traversal {

        private final IdentityHashMap<Object, Boolean> activeContainers = new IdentityHashMap<>();

        private long elements;
        private long logicalSizeBytes;

        private void visit(Object value, OpenType<?> expectedType, int depth) {
            require(depth <= GeneratedJmxWireContract.MAX_DEPTH);
            addElement();
            require(value != null && expectedType != null);
            rejectProhibited(value);

            if (expectedType instanceof SimpleType<?>) {
                visitSimple(value, expectedType);
                return;
            }
            if (expectedType instanceof ArrayType<?> arrayType) {
                visitArray(value, arrayType, depth);
                return;
            }
            if (expectedType instanceof CompositeType compositeType) {
                visitComposite(value, compositeType, depth);
                return;
            }
            if (expectedType instanceof TabularType tabularType) {
                visitTabular(value, tabularType, depth);
                return;
            }
            throw violation();
        }

        private void visitSimple(Object value, OpenType<?> expectedType) {
            Class<?> type = value.getClass();
            require(type.getClassLoader() == null);
            require(ALLOWED_NEUTRAL_TYPES.contains(type.getName()));
            require(expectedType.isValue(value));

            if (value instanceof String text) {
                addString(text);
            } else if (value instanceof ObjectName objectName) {
                require(type == ObjectName.class);
                addString(objectName.getCanonicalName());
            } else {
                addLogicalSize(scalarLogicalSize(value));
            }
        }

        private void visitArray(Object value, ArrayType<?> expectedType, int depth) {
            Class<?> type = value.getClass();
            require(type.isArray());
            require(type.getClassLoader() == null);
            require(ALLOWED_ARRAY_COMPONENTS.contains(type.getComponentType().getName()));
            require(expectedType.isValue(value));

            enter(value);
            try {
                int length = Array.getLength(value);
                requireChildCapacity(length);
                addLogicalSize(Integer.BYTES);
                for (int index = 0; index < length; index++) {
                    visit(Array.get(value, index), expectedType.getElementOpenType(), depth + 1);
                }
            } finally {
                leave(value);
            }
        }

        private void visitComposite(Object value, CompositeType expectedType, int depth) {
            require(value.getClass() == CompositeDataSupport.class);
            CompositeData data = (CompositeData) value;
            validateOpenType(data.getCompositeType(), expectedType);

            enter(value);
            try {
                addString(expectedType.getTypeName());
                List<String> itemNames = new ArrayList<>(expectedType.keySet());
                itemNames.sort(String::compareTo);
                requireChildCapacity(itemNames.size());
                for (String itemName : itemNames) {
                    addString(itemName);
                    visit(data.get(itemName), expectedType.getType(itemName), depth + 1);
                }
            } catch (IllegalArgumentException contractViolation) {
                throw contractViolation;
            } catch (RuntimeException malformedData) {
                throw violation();
            } finally {
                leave(value);
            }
        }

        private void visitTabular(Object value, TabularType expectedType, int depth) {
            require(value.getClass() == TabularDataSupport.class);
            TabularData data = (TabularData) value;
            validateOpenType(data.getTabularType(), expectedType);

            enter(value);
            try {
                addString(expectedType.getTypeName());
                for (String indexName : expectedType.getIndexNames()) {
                    addString(indexName);
                }

                int rowCount = data.size();
                requireChildCapacity(rowCount);
                Collection<?> rows = data.values();
                require(rows != null && rows.size() == rowCount);
                int visitedRows = 0;
                for (Object row : rows) {
                    require(visitedRows < rowCount);
                    visit(row, expectedType.getRowType(), depth + 1);
                    visitedRows++;
                }
                require(visitedRows == rowCount);
            } catch (IllegalArgumentException contractViolation) {
                throw contractViolation;
            } catch (RuntimeException malformedData) {
                throw violation();
            } finally {
                leave(value);
            }
        }

        private void enter(Object value) {
            require(activeContainers.put(value, Boolean.TRUE) == null);
        }

        private void leave(Object value) {
            activeContainers.remove(value);
        }

        private void addElement() {
            require(elements < GeneratedJmxWireContract.MAX_ELEMENTS);
            elements++;
        }

        private void requireChildCapacity(long childCount) {
            require(childCount >= 0
                    && childCount <= GeneratedJmxWireContract.MAX_ELEMENTS - elements);
        }

        private void addLogicalSize(long increment) {
            require(increment >= 0
                    && increment <= GeneratedJmxWireContract.MAX_LOGICAL_BYTES - logicalSizeBytes);
            logicalSizeBytes += increment;
        }

        private void addString(String value) {
            long utf8Bytes = utf8Length(value);
            require(utf8Bytes <= GeneratedJmxWireContract.MAX_STRING_UTF8_BYTES);
            addLogicalSize(utf8Bytes);
        }
    }

    private static void validateOpenType(OpenType<?> actual, OpenType<?> expected) {
        require(actual != null && expected != null);
        require(actual.getClass() == expected.getClass());
        require(expected.getTypeName().equals(actual.getTypeName()));
        require(expected.getClassName().equals(actual.getClassName()));

        if (expected instanceof SimpleType<?>) {
            require(expected.equals(actual));
            return;
        }
        if (expected instanceof ArrayType<?> expectedArray) {
            ArrayType<?> actualArray = (ArrayType<?>) actual;
            require(expectedArray.getDimension() == actualArray.getDimension());
            require(expectedArray.isPrimitiveArray() == actualArray.isPrimitiveArray());
            validateOpenType(actualArray.getElementOpenType(), expectedArray.getElementOpenType());
            return;
        }
        if (expected instanceof CompositeType expectedComposite) {
            CompositeType actualComposite = (CompositeType) actual;
            require(actualComposite.keySet().size() == expectedComposite.keySet().size());
            for (String itemName : expectedComposite.keySet()) {
                require(actualComposite.containsKey(itemName));
                validateOpenType(actualComposite.getType(itemName), expectedComposite.getType(itemName));
            }
            return;
        }
        if (expected instanceof TabularType expectedTable) {
            TabularType actualTable = (TabularType) actual;
            require(expectedTable.getIndexNames().equals(actualTable.getIndexNames()));
            validateOpenType(actualTable.getRowType(), expectedTable.getRowType());
            return;
        }
        throw violation();
    }

    private static void rejectProhibited(Object value) {
        Class<?> type = value.getClass();
        require(!(value instanceof Class<?>));
        require(!(value instanceof ClassLoader));
        require(!(value instanceof Throwable));
        require(!Proxy.isProxyClass(type));
        require(!(type.isSynthetic() && (type.isHidden() || type.getName().contains("$$Lambda$"))));
    }

    private static long scalarLogicalSize(Object value) {
        if (value instanceof Boolean || value instanceof Byte) {
            return Byte.BYTES;
        }
        if (value instanceof Short || value instanceof Character) {
            return Short.BYTES;
        }
        if (value instanceof Integer || value instanceof Float) {
            return Integer.BYTES;
        }
        if (value instanceof Long || value instanceof Double) {
            return Long.BYTES;
        }
        throw violation();
    }

    private static long utf8Length(String value) {
        long length = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current <= 0x7f) {
                length++;
            } else if (current <= 0x7ff) {
                length += 2;
            } else if (Character.isHighSurrogate(current)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                length += 4;
                index++;
            } else {
                length += 3;
            }
        }
        return length;
    }

    private static void requireEmpty(Object[] values) {
        require(values != null && values.length == 0);
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw violation();
        }
    }

    private static IllegalArgumentException violation() {
        return new IllegalArgumentException(CONTRACT_VIOLATION);
    }
}
