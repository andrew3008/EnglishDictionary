package space.br1440.platform.tracing.jmx.contract.tool;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.experimental.UtilityClass;

@UtilityClass
final class JavaContractRenderer {

    private static final List<String> WIRE_GRAPH_INT_KEYS = List.of(
            "maxDepth", "maxElements", "maxLogicalBytes", "maxStringUtf8Bytes",
            "maxRouteEntries", "maxRoutePrefixUtf8Bytes", "maxCapabilityEntries"
    );

    static void render(ContractDocument contract,
                       Path outputRoot) throws IOException {
        Map<String, Object> planes = contract.section("planes");
        renderPlane(contract, packageName(planes, "extension"), outputRoot.resolve("extension"));
        renderPlane(contract, packageName(planes, "application"), outputRoot.resolve("application"));
    }

    private static void renderPlane(ContractDocument contract,
                                    String packageName,
                                    Path planeRoot) throws IOException {
        Path packageDirectory = planeRoot.resolve("java").resolve(packageName.replace('.', '/'));

        JmxWireContractGenerator.writeUtf8(
                packageDirectory.resolve("JmxWireContract.java"),
                renderWireContract(contract, packageName)
        );

        JmxWireContractGenerator.writeUtf8(
                packageDirectory.resolve("JmxOpenTypes.java"),
                renderOpenTypes(contract, packageName)
        );

        JmxWireContractGenerator.writeUtf8(
                packageDirectory.resolve("JmxWireUtf8Meter.java"),
                renderUtf8Meter(packageName)
        );

        JmxWireContractGenerator.writeUtf8(
                packageDirectory.resolve("JmxWireGraphMeter.java"),
                renderWireGraphMeter(packageName)
        );
    }

    private static String renderUtf8Meter(String packageName) {
        return """
                package %s;

                public final class JmxWireUtf8Meter {

                    private JmxWireUtf8Meter() {
                    }

                    public static long utf8Length(String value) {
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
                }
                """.formatted(packageName);
    }

    private static String renderWireGraphMeter(String packageName) {
        return """
                package $PACKAGE$;

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

                public final class JmxWireGraphMeter {

                    private static final TypePolicy TYPE_POLICY = new TypePolicy(
                            Set.copyOf(JmxWireContract.ALLOWED_NEUTRAL_TYPES),
                            Set.copyOf(JmxWireContract.ALLOWED_ARRAY_COMPONENTS)
                    );

                    private JmxWireGraphMeter() {
                    }

                    public static long measure(Object value) {
                        GraphWalk walk = new GraphWalk(TYPE_POLICY);
                        walk.visit(value, 0);
                        return walk.logicalSizeBytes();
                    }

                    private static final class TypePolicy {

                        private final Set<String> allowedNeutralTypes;
                        private final Set<String> allowedArrayComponents;

                        private TypePolicy(Set<String> allowedNeutralTypes,
                                           Set<String> allowedArrayComponents) {
                            this.allowedNeutralTypes = allowedNeutralTypes;
                            this.allowedArrayComponents = allowedArrayComponents;
                        }

                        private void requireAllowedNeutralType(Class<?> type) {
                            if (!allowedNeutralTypes.contains(type.getName())) {
                                throw violation("type is not on the neutral allow-list: " + type.getName());
                            }
                        }

                        private void requireAllowedArrayComponent(Class<?> component) {
                            if (!allowedArrayComponents.contains(component.getName())) {
                                throw violation("array component type is not allowed: " + component.getName());
                            }
                        }
                    }

                    private static final class GraphBudget {

                        private long elements;
                        private long logicalSizeBytes;

                        private void requireDepth(int depth) {
                            if (depth > JmxWireContract.MAX_DEPTH) {
                                String message = "max nesting depth exceeded: depth=%d, limit=%d"
                                        .formatted(depth, JmxWireContract.MAX_DEPTH);
                                throw violation(message);
                            }
                        }

                        private void chargeElement() {
                            if (elements >= JmxWireContract.MAX_ELEMENTS) {
                                throw violation(
                                        "max element count exceeded: limit=" + JmxWireContract.MAX_ELEMENTS
                                );
                            }
                            elements++;
                        }

                        private void requireChildCapacity(long childCount) {
                            if (childCount < 0 || childCount > JmxWireContract.MAX_ELEMENTS - elements) {
                                throw violation(
                                        "declared child count exceeds remaining element budget: childCount=" + childCount
                                );
                            }
                        }

                        private void chargeLogicalSize(long increment) {
                            if (increment < 0
                                    || increment > JmxWireContract.MAX_LOGICAL_BYTES - logicalSizeBytes) {
                                throw violation(
                                        "max logical size exceeded: limit=" + JmxWireContract.MAX_LOGICAL_BYTES
                                );
                            }
                            logicalSizeBytes += increment;
                        }

                        private void chargeStringLogicalSize(String value) {
                            long utf8Bytes = JmxWireUtf8Meter.utf8Length(value);
                            if (utf8Bytes > JmxWireContract.MAX_STRING_UTF8_BYTES) {
                                throw violation(
                                        "string exceeds max UTF-8 byte length: limit="
                                                + JmxWireContract.MAX_STRING_UTF8_BYTES
                                );
                            }
                            chargeLogicalSize(utf8Bytes);
                        }

                        private long logicalSizeBytes() {
                            return logicalSizeBytes;
                        }
                    }

                    private static final class VisitedContainers {

                        private final Set<Object> containers =
                                Collections.newSetFromMap(new IdentityHashMap<>());

                        private void enter(Object container) {
                            if (!containers.add(container)) {
                                throw violation("cycle detected in object graph");
                            }
                        }

                        private void leave(Object container) {
                            containers.remove(container);
                        }
                    }

                    private static final class GraphWalk {

                        private final TypePolicy typePolicy;
                        private final GraphBudget budget = new GraphBudget();
                        private final VisitedContainers visitedContainers = new VisitedContainers();

                        private GraphWalk(TypePolicy typePolicy) {
                            this.typePolicy = typePolicy;
                        }

                        private long logicalSizeBytes() {
                            return budget.logicalSizeBytes();
                        }

                        private void visit(Object value, int depth) {
                            budget.requireDepth(depth);
                            budget.chargeElement();

                            if (value == null) {
                                throw violation("null is not a valid neutral value");
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
                                default -> throw violation(
                                        "unsupported type in JMX wire graph: " + type.getName()
                                );
                            }
                        }

                        private void chargeScalar(Class<?> type, long widthBytes) {
                            typePolicy.requireAllowedNeutralType(type);
                            budget.chargeLogicalSize(widthBytes);
                        }

                        private void visitObjectName(ObjectName objectName, Class<?> type) {
                            typePolicy.requireAllowedNeutralType(ObjectName.class);
                            if (type != ObjectName.class || type.getClassLoader() != null) {
                                throw violation(
                                        "ObjectName must be the exact bootstrap implementation, got: "
                                                + type.getName()
                                );
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
                                    throw violation("CompositeData.getCompositeType() returned null");
                                }

                                String declaredTypeName = readForeign(compositeType::getTypeName);
                                if (declaredTypeName == null) {
                                    throw violation("CompositeType.getTypeName() returned null");
                                }
                                budget.chargeStringLogicalSize(declaredTypeName);

                                List<?> rawItemNames =
                                        readForeign(() -> List.copyOf(compositeType.keySet()));
                                budget.requireChildCapacity(rawItemNames.size());

                                List<String> itemNames = new ArrayList<>(rawItemNames.size());
                                for (Object rawItemName : rawItemNames) {
                                    if (!(rawItemName instanceof String itemName)) {
                                        throw violation(
                                                "CompositeType key is not a String: " + rawItemName
                                        );
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
                                    throw violation("TabularData.getTabularType() returned null");
                                }

                                String declaredTypeName = readForeign(tabularType::getTypeName);
                                if (declaredTypeName == null) {
                                    throw violation("TabularType.getTypeName() returned null");
                                }
                                budget.chargeStringLogicalSize(declaredTypeName);

                                List<?> indexNames =
                                        readForeign(() -> List.copyOf(tabularType.getIndexNames()));
                                budget.requireChildCapacity(indexNames.size());
                                for (Object indexName : indexNames) {
                                    if (!(indexName instanceof String text)) {
                                        throw violation(
                                                "TabularType index name is not a String: " + indexName
                                        );
                                    }
                                    budget.chargeStringLogicalSize(text);
                                }

                                int declaredRows = readForeign(data::size);
                                budget.requireChildCapacity(declaredRows);
                                List<?> rows =
                                        readForeign(() -> new ArrayList<>(data.values()));
                                if (rows.size() != declaredRows) {
                                    String message = "TabularData.size() disagrees with values() length: "
                                            + "declared=%d, actual=%d"
                                            .formatted(declaredRows, rows.size());
                                    throw violation(message);
                                }

                                for (Object row : rows) {
                                    if (!(row instanceof CompositeData)) {
                                        throw violation(
                                                "TabularData row is not CompositeData: " + row
                                        );
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
                    }

                    private static <T> T readForeign(Supplier<T> foreignAccessor) {
                        try {
                            return foreignAccessor.get();
                        } catch (RuntimeException malformedData) {
                            throw violation(
                                    "foreign implementation threw while reading graph data: "
                                            + malformedData.getClass().getName(),
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
                            throw violation("explicitly prohibited type: " + type.getName());
                        }
                    }

                    private static boolean isLambdaClass(Class<?> type) {
                        return type.isSynthetic() && type.isHidden();
                    }

                    private static void requireBootstrapImplementation(Class<?> type) {
                        if (type.getClassLoader() != null) {
                            throw violation(
                                    "expected bootstrap classloader implementation, got: " + type.getName()
                            );
                        }
                    }

                    private static IllegalArgumentException violation(String message) {
                        return new IllegalArgumentException(message);
                    }

                    private static IllegalArgumentException violation(String message,
                                                                      RuntimeException cause) {
                        return new IllegalArgumentException(message, cause);
                    }
                }
                """.replace("$PACKAGE$", packageName);
    }

    private static String renderWireContract(ContractDocument contract, String packageName) {
        StringBuilder source = new StringBuilder();
        source.append("package ").append(packageName).append(";\n\n")
                .append("import java.util.List;\n")
                .append("import java.util.Set;\n\n")
                .append("import javax.management.MalformedObjectNameException;\n")
                .append("import javax.management.ObjectName;\n\n")
                .append("public final class JmxWireContract {\n\n")
                .append("    public static final String SCHEMA_ID = ")
                .append(quoted(contract.schemaId())).append(";\n")
                .append("    public static final String SCHEMA_DIGEST_SHA256 = ")
                .append(quoted(contract.schemaDigest())).append(";\n")
                .append("    public static final String DESCRIPTOR_DIGEST_SHA256 = ")
                .append(quoted(contract.descriptorDigest())).append(";\n\n");

        Map<String, Object> objectNames = contract.section("objectNames");
        for (Map.Entry<String, Object> entry : objectNames.entrySet()) {
            String constant = upperSnake(entry.getKey());
            source.append("    public static final String ").append(constant)
                    .append("_OBJECT_NAME_STR = ").append(quoted((String) entry.getValue())).append(";\n")
                    .append("    public static final ObjectName ").append(constant)
                    .append("_OBJECT_NAME = objectName(").append(constant).append("_OBJECT_NAME_STR);\n");
        }
        source.append('\n');

        Map<String, Object> endpoints = contract.section("endpoints");
        for (Map.Entry<String, Object> endpointEntry : endpoints.entrySet()) {
            String endpointConstant = upperSnake(endpointEntry.getKey());
            Map<String, Object> endpoint = ContractValues.map(
                    endpointEntry.getValue(),
                    "endpoints." + endpointEntry.getKey()
            );

            for (Object rawAttribute : ContractValues.list(
                    endpoint.get("attributes"), "endpoints." + endpointEntry.getKey() + ".attributes")) {
                Map<String, Object> attribute = ContractValues.map(rawAttribute, "endpoint.attribute");
                source.append("    public static final String ATTRIBUTE_").append(endpointConstant).append('_')
                        .append(upperSnake((String) attribute.get("name"))).append(" = ")
                        .append(quoted((String) attribute.get("name"))).append(";\n");
            }

            String path = "endpoints." + endpointEntry.getKey() + ".operations";
            for (Object rawOperation : ContractValues.list(endpoint.get("operations"), path)) {
                Map<String, Object> operation = ContractValues.map(rawOperation, "endpoint.operation");
                String operationConstant = "OPERATION_" + endpointConstant + '_' + upperSnake((String) operation.get("name"));
                source.append("    public static final String ")
                        .append(operationConstant).append(" = ")
                        .append(quoted((String) operation.get("name"))).append(";\n");

                for (Object rawParameter : ContractValues.list(operation.get("parameters"), "endpoint.operation.parameters")) {
                    Map<String, Object> parameter = ContractValues.map(rawParameter, "endpoint.operation.parameter");
                    source.append("    public static final String PARAMETER_")
                            .append(operationConstant.substring("OPERATION_".length())).append('_')
                            .append(upperSnake((String) parameter.get("name"))).append(" = ")
                            .append(quoted((String) parameter.get("name"))).append(";\n");
                }
            }
        }
        source.append('\n');

        Map<String, Object> openTypes = contract.section("openTypes");
        for (Map.Entry<String, Object> typeEntry : openTypes.entrySet()) {
            String typeConstant = upperSnake(typeEntry.getKey());

            Map<String, Object> descriptor = ContractValues.map(typeEntry.getValue(), "openTypes." + typeEntry.getKey());
            source.append("    public static final String OPEN_TYPE_").append(typeConstant)
                    .append("_TYPE_NAME = ").append(quoted((String) descriptor.get("typeName"))).append(";\n");

            if ("composite".equals(descriptor.get("kind"))) {

                String path = "openTypes." + typeEntry.getKey() + ".fields";
                for (Object rawField : ContractValues.list(descriptor.get("fields"), path)) {
                    Map<String, Object> field = ContractValues.map(rawField, "openType.field");

                    String fieldConstant = "FIELD_" + typeConstant + '_' + upperSnake((String) field.get("name"));
                    source.append("    public static final String ").append(fieldConstant).append(" = ")
                            .append(quoted((String) field.get("name"))).append(";\n");
                }
            } else if ("map".equals(descriptor.get("kind"))) {
                source.append("    public static final String FIELD_").append(typeConstant).append("_KEY = ")
                        .append(quoted((String) descriptor.get("keyField"))).append(";\n")
                        .append("    public static final String FIELD_").append(typeConstant).append("_VALUE = ")
                        .append(quoted((String) descriptor.get("valueField"))).append(";\n");

                if (descriptor.containsKey("keys")) {
                    for (String wireKey : ContractValues.strings(
                            descriptor.get("keys"), "openTypes." + typeEntry.getKey() + ".keys")) {
                        source.append("    public static final String KEY_").append(typeConstant).append('_')
                                .append(upperSnake(wireKey)).append(" = ")
                                .append(quoted(wireKey)).append(";\n");
                    }
                }
            }
        }
        source.append('\n');

        Map<String, Object> wireGraph = contract.section("wireGraph");
        for (String key : WIRE_GRAPH_INT_KEYS) {
            source.append("    public static final int ").append(upperSnake(key)).append(" = ")
                    .append(wireGraph.get(key)).append(";\n");
        }
        source.append("    public static final List<String> ALLOWED_NEUTRAL_TYPES = List.of(")
                .append(joinQuoted(ContractValues.strings(
                        wireGraph.get("allowedNeutralTypes"), "wireGraph.allowedNeutralTypes")))
                .append(");\n")
                .append("    public static final List<String> ALLOWED_ARRAY_COMPONENTS = List.of(")
                .append(joinQuoted(ContractValues.strings(
                        wireGraph.get("allowedArrayComponents"), "wireGraph.allowedArrayComponents")))
                .append(");\n");
        appendTokenSet(source, contract, "VALID_WRITERS", "writer");
        appendTokenSet(source, contract, "VALID_SOURCE_AUTHORITIES", "sourceAuthority");
        appendTokenSet(source, contract, "VALID_OUTCOMES", "outcome");
        appendTokenSet(source, contract, "VALID_MESSAGE_CODES", "messageCode");
        appendTokenSet(source, contract, "VALID_LOG_LEVELS", "logLevel");
        appendTokenSet(source, contract, "VALID_VALIDATION_LEVELS", "validationLevel");
        appendTokenSet(source, contract, "VALID_SCANNER_STATES", "scannerState");
        appendTokenSet(source, contract, "VALID_PROCESSOR_LIFECYCLES", "processorLifecycle");
        appendTokenSet(source, contract, "VALID_READINESS_LIFECYCLES", "readinessLifecycle");
        source
                .append("    public static final List<String> REQUIRED_CAPABILITIES = List.of(")
                .append(joinQuoted(ContractValues.strings(
                        contract.section("capabilities").get("required"), "capabilities.required")))
                .append(");\n");

        source.append('\n')
                .append("    private JmxWireContract() {\n")
                .append("    }\n\n")
                .append("    private static ObjectName objectName(String value) {\n")
                .append("        try {\n")
                .append("            return ObjectName.getInstance(value);\n")
                .append("        } catch (MalformedObjectNameException exception) {\n")
                .append("            throw new ExceptionInInitializerError(exception);\n")
                .append("        }\n")
                .append("    }\n")
                .append("}\n");
        return source.toString();
    }

    private static void appendTokenSet(StringBuilder source,
                                       ContractDocument contract,
                                       String constantName,
                                       String sectionName) {
        source.append("    public static final Set<String> ").append(constantName).append(" = Set.of(")
                .append(joinQuoted(ContractValues.strings(
                        contract.section(sectionName).get("values"),
                        sectionName + ".values"
                )))
                .append(");\n");
    }

    private static String renderOpenTypes(ContractDocument contract, String packageName) {
        Map<String, Object> openTypes = contract.section("openTypes");
        List<String> order = dependencyOrder(openTypes);

        StringBuilder source = new StringBuilder();
        source.append("package ").append(packageName).append(";\n\n")
                .append("import javax.management.openmbean.ArrayType;\n")
                .append("import javax.management.openmbean.CompositeType;\n")
                .append("import javax.management.openmbean.OpenDataException;\n")
                .append("import javax.management.openmbean.OpenType;\n")
                .append("import javax.management.openmbean.SimpleType;\n")
                .append("import javax.management.openmbean.TabularType;\n\n")
                .append("public final class JmxOpenTypes {\n\n");

        for (String typeId : order) {
            Map<String, Object> descriptor = ContractValues.map(openTypes.get(typeId), "openTypes." + typeId);
            String javaType = "composite".equals(descriptor.get("kind")) ? "CompositeType" : "TabularType";
            source.append("    public static final ").append(javaType).append(' ')
                    .append(upperSnake(typeId)).append(";\n");
        }

        source.append("\n    static {\n")
                .append("        try {\n");
        for (String typeId : order) {
            appendOpenTypeInitializer(source, typeId, ContractValues.map(
                    openTypes.get(typeId), "openTypes." + typeId));
        }
        source.append("        } catch (OpenDataException exception) {\n")
                .append("            throw new ExceptionInInitializerError(exception);\n")
                .append("        }\n")
                .append("    }\n\n")
                .append("    private JmxOpenTypes() {\n")
                .append("    }\n\n")
                .append("    private static CompositeType composite(\n")
                .append("            String typeName,\n")
                .append("            String[] itemNames,\n")
                .append("            OpenType<?>[] itemTypes\n")
                .append("    ) throws OpenDataException {\n")
                .append("        return new CompositeType(typeName, typeName, itemNames, itemNames, itemTypes);\n")
                .append("    }\n\n")
                .append("    private static ArrayType<String> stringArray() throws OpenDataException {\n")
                .append("        return new ArrayType<>(1, SimpleType.STRING);\n")
                .append("    }\n")
                .append("}\n");
        return source.toString();
    }

    private static void appendOpenTypeInitializer(StringBuilder source,
                                                  String typeId,
                                                  Map<String, Object> descriptor) {
        String constant = upperSnake(typeId);
        String kind = (String) descriptor.get("kind");
        if ("composite".equals(kind)) {
            List<Object> fields = ContractValues.list(descriptor.get("fields"), "openTypes." + typeId + ".fields");
            List<String> names = new ArrayList<>();
            List<String> types = new ArrayList<>();
            for (Object rawField : fields) {
                Map<String, Object> field = ContractValues.map(rawField, "openType.field");
                names.add((String) field.get("name"));
                types.add(openTypeExpression((String) field.get("type")));
            }

            source.append("            ").append(constant).append(" = composite(\n")
                    .append("                    ").append(quoted((String) descriptor.get("typeName"))).append(",\n")
                    .append("                    new String[]{").append(joinQuoted(names)).append("},\n")
                    .append("                    new OpenType<?>[]{").append(String.join(", ", types)).append("}\n")
                    .append("            );\n");
        } else if ("tabular".equals(kind)) {
            source.append("            ").append(constant).append(" = new TabularType(\n")
                    .append("                    ").append(quoted((String) descriptor.get("typeName"))).append(",\n")
                    .append("                    ").append(quoted((String) descriptor.get("typeName"))).append(",\n")
                    .append("                    ").append(upperSnake((String) descriptor.get("rowType"))).append(",\n")
                    .append("                    new String[]{")
                    .append(joinQuoted(ContractValues.strings(
                            descriptor.get("indexFields"), "openType.indexFields"))).append("}\n")
                    .append("            );\n");
        } else {
            String rowVariable = lowerCamel(typeId) + "Row";
            source.append("            CompositeType ").append(rowVariable).append(" = composite(\n")
                    .append("                    ").append(quoted((String) descriptor.get("rowTypeName"))).append(",\n")
                    .append("                    new String[]{")
                    .append(joinQuoted(List.of(
                            (String) descriptor.get("keyField"),
                            (String) descriptor.get("valueField")
                    ))).append("},\n")
                    .append("                    new OpenType<?>[]{")
                    .append(openTypeExpression((String) descriptor.get("keyType"))).append(", ")
                    .append(openTypeExpression((String) descriptor.get("valueType"))).append("}\n")
                    .append("            );\n")
                    .append("            ").append(constant).append(" = new TabularType(\n")
                    .append("                    ").append(quoted((String) descriptor.get("typeName"))).append(",\n")
                    .append("                    ").append(quoted((String) descriptor.get("typeName"))).append(",\n")
                    .append("                    ").append(rowVariable).append(",\n")
                    .append("                    new String[]{")
                    .append(joinQuoted(ContractValues.strings(
                            descriptor.get("indexFields"), "openType.indexFields"))).append("}\n")
                    .append("            );\n");
        }
    }

    private static String openTypeExpression(String type) {
        return switch (type) {
            case "boolean" -> "SimpleType.BOOLEAN";
            case "int" -> "SimpleType.INTEGER";
            case "long" -> "SimpleType.LONG";
            case "double" -> "SimpleType.DOUBLE";
            case "string" -> "SimpleType.STRING";
            case "objectName" -> "SimpleType.OBJECTNAME";
            case "stringArray" -> "stringArray()";
            default -> upperSnake(type.substring(type.indexOf(':') + 1));
        };
    }

    private static List<String> dependencyOrder(Map<String, Object> openTypes) {
        List<String> result = new ArrayList<>();
        Set<String> complete = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (String type : openTypes.keySet()) {
            addWithDependencies(type, openTypes, visiting, complete, result);
        }

        return result;
    }

    private static void addWithDependencies(String type,
                                            Map<String, Object> openTypes,
                                            Set<String> visiting,
                                            Set<String> complete,
                                            List<String> result) {
        if (complete.contains(type)) {
            return;
        }

        if (!visiting.add(type)) {
            throw new IllegalStateException("Open Type dependency cycle: " + type);
        }

        Map<String, Object> descriptor = ContractValues.map(openTypes.get(type), "openTypes." + type);
        if ("composite".equals(descriptor.get("kind"))) {
            for (Object rawField : ContractValues.list(descriptor.get("fields"), "openTypes." + type + ".fields")) {
                String fieldType = (String) ContractValues.map(rawField, "openType.field").get("type");
                if (fieldType.contains(":")) {
                    addWithDependencies(
                            fieldType.substring(fieldType.indexOf(':') + 1),
                            openTypes, visiting, complete, result
                    );
                }
            }
        } else if ("tabular".equals(descriptor.get("kind"))) {
            addWithDependencies((String) descriptor.get("rowType"), openTypes, visiting, complete, result);
        }

        visiting.remove(type);
        complete.add(type);
        result.add(type);
    }

    private static String packageName(Map<String, Object> planes, String plane) {
        return (String) ContractValues.map(planes.get(plane), "planes." + plane).get("packageName");
    }

    private static String upperSnake(String value) {
        return ContractValues.javaConstantName(value);
    }

    private static String lowerCamel(String value) {
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private static String joinQuoted(List<String> values) {
        List<String> quoted = new ArrayList<>(values.size());
        for (String value : values) {
            quoted.add(quoted(value));
        }

        return String.join(", ", quoted);
    }

    private static String quoted(String value) {
        return '"' + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + '"';
    }
}
