package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularType;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireUtf8Meter;

public final class JmxWireGraphValidator {

    private final TypePolicy typePolicy;

    public JmxWireGraphValidator() {
        this.typePolicy = new TypePolicy(
                Set.copyOf(JmxWireContract.ALLOWED_NEUTRAL_TYPES),
                Set.copyOf(JmxWireContract.ALLOWED_ARRAY_COMPONENTS)
        );
    }

    public void validate(Object value) {
        new GraphWalk(typePolicy)
                .visit(value, 0);
    }

    private static final class TypePolicy {

        private final Set<String> allowedNeutralTypes;
        private final Set<String> allowedArrayComponents;

        TypePolicy(Set<String> allowedNeutralTypes, Set<String> allowedArrayComponents) {
            this.allowedNeutralTypes = allowedNeutralTypes;
            this.allowedArrayComponents = allowedArrayComponents;
        }

        void requireAllowedNeutralType(Class<?> type) {
            if (!allowedNeutralTypes.contains(type.getName())) {
                throw new JmxWireTypeViolationException("type is not on the neutral allow-list: " + type.getName());
            }
        }

        void requireAllowedArrayComponent(Class<?> component) {
            if (!allowedArrayComponents.contains(component.getName())) {
                throw new JmxWireTypeViolationException("array component type is not allowed: " + component.getName());
            }
        }
    }

    private static final class GraphBudget {

        private long elements;
        private long logicalSizeBytes;

        void requireDepth(int depth) {
            if (depth > JmxWireContract.MAX_DEPTH) {
                String message = "max nesting depth exceeded: depth=%d, limit=%d"
                        .formatted(depth, JmxWireContract.MAX_DEPTH);

                throw new JmxWireTypeViolationException(message);
            }
        }

        void chargeElement() {
            if (elements >= JmxWireContract.MAX_ELEMENTS) {
                throw new JmxWireTypeViolationException("max element count exceeded: limit=" + JmxWireContract.MAX_ELEMENTS);
            }

            elements++;
        }

        void requireChildCapacity(long childCount) {
            if (childCount < 0 || childCount > JmxWireContract.MAX_ELEMENTS - elements) {
                throw new JmxWireTypeViolationException("declared child count exceeds remaining element budget: childCount=" + childCount);
            }
        }

        void chargeLogicalSize(long increment) {
            if (increment < 0 || increment > JmxWireContract.MAX_LOGICAL_BYTES - logicalSizeBytes) {
                throw new JmxWireTypeViolationException("max logical size exceeded: limit=" + JmxWireContract.MAX_LOGICAL_BYTES);
            }

            logicalSizeBytes += increment;
        }

        void chargeStringLogicalSize(String value) {
            long utf8Bytes = JmxWireUtf8Meter.utf8Length(value);
            if (utf8Bytes > JmxWireContract.MAX_STRING_UTF8_BYTES) {
                throw new JmxWireTypeViolationException("string exceeds max UTF-8 byte length: limit=" + JmxWireContract.MAX_STRING_UTF8_BYTES);
            }

            chargeLogicalSize(utf8Bytes);
        }
    }

    private static final class VisitedContainers {

        private final Set<Object> containers = Collections.newSetFromMap(new IdentityHashMap<>());

        void enter(Object container) {
            if (!containers.add(container)) {
                throw new JmxWireTypeViolationException("cycle detected in object graph");
            }
        }

        void leave(Object container) {
            containers.remove(container);
        }
    }

    private static final class GraphWalk {

        private final TypePolicy typePolicy;
        private final GraphBudget budget = new GraphBudget();
        private final VisitedContainers visitedContainers = new VisitedContainers();

        GraphWalk(TypePolicy typePolicy) {
            this.typePolicy = typePolicy;
        }

        void visit(Object value, int depth) {
            budget.requireDepth(depth);
            budget.chargeElement();

            if (value == null) {
                throw new JmxWireTypeViolationException("null is not a valid neutral value");
            }

            Class<?> type = value.getClass();
            rejectExplicitlyProhibited(value, type);

            if (type.isArray()) {
                visitArray(value, type, depth);
                return;
            }

            switch (value) {
                case Boolean ignored -> chargeScalar(type, Byte.BYTES);
                case Byte ignored -> chargeScalar(type, Byte.BYTES);
                case Short ignored -> chargeScalar(type, Short.BYTES);
                case Character ignored -> chargeScalar(type, Short.BYTES);
                case Integer ignored -> chargeScalar(type, Integer.BYTES);
                case Float ignored -> chargeScalar(type, Integer.BYTES);
                case Long ignored -> chargeScalar(type, Long.BYTES);
                case Double ignored -> chargeScalar(type, Long.BYTES);

                case String text -> {
                    typePolicy.requireAllowedNeutralType(type);
                    budget.chargeStringLogicalSize(text);
                }

                case ObjectName objectName -> visitObjectName(objectName, type);

                case CompositeData compositeData -> {
                    typePolicy.requireAllowedNeutralType(CompositeData.class);
                    requireBootstrapImplementation(type);
                    visitComposite(compositeData, depth);
                }

                case TabularData tabularData -> {
                    typePolicy.requireAllowedNeutralType(TabularData.class);
                    requireBootstrapImplementation(type);
                    visitTabular(tabularData, depth);
                }

                default -> throw new JmxWireTypeViolationException("unsupported type in JMX wire graph: " + type.getName());
            }
        }

        private void chargeScalar(Class<?> type, long widthBytes) {
            typePolicy.requireAllowedNeutralType(type);
            budget.chargeLogicalSize(widthBytes);
        }

        private void visitObjectName(ObjectName objectName, Class<?> type) {
            typePolicy.requireAllowedNeutralType(ObjectName.class);
            if (type != ObjectName.class || type.getClassLoader() != null) {
                throw new JmxWireTypeViolationException("ObjectName must be the exact bootstrap implementation, got: " + type.getName());
            }

            budget.chargeStringLogicalSize(objectName.getCanonicalName());
        }

        private void visitArray(Object array, Class<?> type, int depth) {
            typePolicy.requireAllowedArrayComponent(type.getComponentType());
            withContainerGuard(array, () -> {
                int length = Array.getLength(array);
                budget.requireChildCapacity(length);
                budget.chargeLogicalSize(Integer.BYTES);

                for (int index = 0; index < length; index++) {
                    visit(Array.get(array, index), depth + 1);
                }
            });
        }

        private void visitComposite(CompositeData data, int depth) {
            withContainerGuard(data, () -> {
                CompositeType compositeType = readForeign(data::getCompositeType);
                if (compositeType == null) {
                    throw new JmxWireTypeViolationException("CompositeData.getCompositeType() returned null");
                }

                String declaredTypeName = readForeign(compositeType::getTypeName);
                if (declaredTypeName == null) {
                    throw new JmxWireTypeViolationException("CompositeType.getTypeName() returned null");
                }
                budget.chargeStringLogicalSize(declaredTypeName);

                List<?> rawItemNames = readForeign(() -> List.copyOf(compositeType.keySet()));
                budget.requireChildCapacity(rawItemNames.size());

                List<String> itemNames = new ArrayList<>(rawItemNames.size());
                for (Object rawItemName : rawItemNames) {
                    if (!(rawItemName instanceof String itemName)) {
                        throw new JmxWireTypeViolationException("CompositeType key is not a String: " + rawItemName);
                    }

                    budget.chargeStringLogicalSize(itemName);
                    itemNames.add(itemName);
                }
                itemNames.sort(String::compareTo);

                for (String itemName : itemNames) {
                    Object item = readForeign(() -> data.get(itemName));
                    visit(item, depth + 1);
                }
            });
        }

        private void visitTabular(TabularData data, int depth) {
            withContainerGuard(data, () -> {
                TabularType tabularType = readForeign(data::getTabularType);
                if (tabularType == null) {
                    throw new JmxWireTypeViolationException("TabularData.getTabularType() returned null");
                }

                String declaredTypeName = readForeign(tabularType::getTypeName);
                if (declaredTypeName == null) {
                    throw new JmxWireTypeViolationException("TabularType.getTypeName() returned null");
                }
                budget.chargeStringLogicalSize(declaredTypeName);

                List<?> indexNames = readForeign(() -> List.copyOf(tabularType.getIndexNames()));
                budget.requireChildCapacity(indexNames.size());
                for (Object indexName : indexNames) {
                    if (!(indexName instanceof String text)) {
                        throw new JmxWireTypeViolationException("TabularType index name is not a String: " + indexName);
                    }
                    budget.chargeStringLogicalSize(text);
                }

                int declaredRows = readForeign(data::size);
                budget.requireChildCapacity(declaredRows);

                List<?> rows = readForeign(() -> new ArrayList<>(data.values()));
                if (rows.size() != declaredRows) {
                    String message = "TabularData.size() disagrees with values() length: declared=%d, actual=%d"
                            .formatted(declaredRows, rows.size());
                    throw new JmxWireTypeViolationException(message);
                }

                for (Object row : rows) {
                    if (!(row instanceof CompositeData)) {
                        throw new JmxWireTypeViolationException("TabularData row is not CompositeData: " + row);
                    }

                    visit(row, depth + 1);
                }
            });
        }

        private void withContainerGuard(Object container, Runnable body) {
            visitedContainers.enter(container);
            try {
                body.run();
            } finally {
                visitedContainers.leave(container);
            }
        }

        private static <T> T readForeign(Supplier<T> foreignAccessor) {
            try {
                return foreignAccessor.get();
            } catch (RuntimeException malformedData) {
                throw new JmxWireTypeViolationException(
                        "foreign implementation threw while reading graph data: " + malformedData.getClass().getName(),
                        malformedData
                );
            }
        }

        private static void rejectExplicitlyProhibited(Object value, Class<?> type) {
            if (value instanceof Class<?>
                    || value instanceof ClassLoader
                    || value instanceof Throwable
                    || Proxy.isProxyClass(type)
                    || isLambdaClass(type)) {
                throw new JmxWireTypeViolationException("explicitly prohibited type: " + type.getName());
            }
        }

        private static boolean isLambdaClass(Class<?> type) {
            return type.isSynthetic() && type.isHidden();
        }

        private static void requireBootstrapImplementation(Class<?> type) {
            if (type.getClassLoader() != null) {
                throw new JmxWireTypeViolationException("expected bootstrap classloader implementation, got: " + type.getName());
            }
        }
    }
}
