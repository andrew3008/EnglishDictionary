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
            "maxRouteEntries", "maxRoutePrefixUtf8Bytes", "maxCapabilityEntries");

    static void render(ContractDocument contract,
                       Path outputRoot) throws IOException {
        Map<String, Object> planes = contract.section("planes");
        renderPlane(contract, "extension", packageName(planes, "extension"), outputRoot.resolve("extension"));
        renderPlane(contract, "application", packageName(planes, "application"), outputRoot.resolve("application"));
    }

    private static void renderPlane(ContractDocument contract,
                                    String plane,
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
                packageDirectory.resolve("JmxTracingSettingsDigest.java"),
                renderDigest(contract, packageName)
        );

        JmxWireContractGenerator.writeUtf8(
                packageDirectory.resolve("JmxWireUtf8Meter.java"),
                renderUtf8Meter(packageName)
        );

        writeIdentity(contract, plane, planeRoot.resolve("resources"));
    }

    private static String renderUtf8Meter(String packageName) {
        return """
                package %s;

                /**
                 * Измеряет длину строки в байтах UTF-8 без выделения массива.
                 *
                 * <p>Незакрытая или некорректная пара суррогатов считается тремя байтами, как это делает
                 * кодировщик JDK при подстановке replacement character.</p>
                 */
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

    private static String renderWireContract(ContractDocument contract, String packageName) {
        StringBuilder source = new StringBuilder();
        source.append("package ").append(packageName).append(";\n\n")
                .append("import java.util.List;\n\n")
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
                .append(");\n")
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

    private static String renderDigest(ContractDocument contract, String packageName) {
        Map<String, Object> policyDigest = contract.section("policyDigest");
        Map<String, Object> wireGraph = contract.section("wireGraph");
        StringBuilder source = new StringBuilder();
        source.append("package ").append(packageName).append(";\n\n")
                .append("import java.io.ByteArrayOutputStream;\n")
                .append("import java.io.DataOutputStream;\n")
                .append("import java.io.IOException;\n")
                .append("import java.nio.charset.StandardCharsets;\n")
                .append("import java.security.MessageDigest;\n")
                .append("import java.security.NoSuchAlgorithmException;\n")
                .append("import java.util.ArrayList;\n")
                .append("import java.util.Comparator;\n")
                .append("import java.util.HexFormat;\n")
                .append("import java.util.List;\n")
                .append("import java.util.Map;\n")
                .append("import java.util.Objects;\n")
                .append("import java.util.Set;\n\n")
                .append("public final class JmxTracingSettingsDigest {\n\n")
                .append("    private static final byte[] DOMAIN_SEPARATOR = ")
                .append(quoted((String) policyDigest.get("domainSeparator")))
                .append(".getBytes(StandardCharsets.UTF_8);\n")
                .append("    private static final int ENCODING_VERSION = ")
                .append(policyDigest.get("encodingVersion")).append(";\n")
                .append("    private static final int MAX_ROUTE_ENTRIES = ")
                .append(wireGraph.get("maxRouteEntries")).append(";\n")
                .append("    private static final int MAX_ROUTE_PREFIX_UTF8_BYTES = ")
                .append(wireGraph.get("maxRoutePrefixUtf8Bytes")).append(";\n")
                .append("    private static final Set<String> VALID_LOG_LEVELS = Set.of(")
                .append(joinQuoted(ContractValues.strings(
                        policyDigest.get("validLogLevels"), "policyDigest.validLogLevels")))
                .append(");\n")
                .append("    private static final Comparator<EncodedRouteRatio> ROUTE_PREFIX_ORDER =\n")
                .append("            Comparator.comparing(\n")
                .append("                    EncodedRouteRatio::routePrefix, JmxTracingSettingsDigest::compareUnsigned\n")
                .append("            );\n\n")
                .append("    private JmxTracingSettingsDigest() {\n")
                .append("    }\n\n")
                .append("    public static String sha256(\n");

        List<ContractSchema.PolicyDigestField> fields = ContractSchema.POLICY_DIGEST_FIELDS;
        List<String> parameterLines = new ArrayList<>();
        for (ContractSchema.PolicyDigestField field : fields) {
            parameterLines.add("            " + javaParameterType(field.type()) + " " + field.name());
        }
        source.append(String.join(",\n", parameterLines)).append("\n")
                .append("    ) {\n");

        for (ContractSchema.PolicyDigestField field : fields) {
            if (requiresNonNullCheck(field.type())) {
                source.append("        Objects.requireNonNull(").append(field.name())
                        .append(", ").append(quoted(field.name())).append(");\n");
            }
        }

        source.append("        requireRatio(samplingRatio, ").append(quoted("samplingRatio")).append(");\n")
                .append("        if (!VALID_LOG_LEVELS.contains(diagnosticsLogLevel)) {\n")
                .append("            throw new IllegalArgumentException(")
                .append(quoted("Unsupported diagnostics log level: ")).append(" + diagnosticsLogLevel);\n")
                .append("        }\n")
                .append("        if (samplingRouteRatios.size() > MAX_ROUTE_ENTRIES) {\n")
                .append("            throw new IllegalArgumentException(")
                .append(quoted("Too many sampling route ratios")).append(");\n")
                .append("        }\n")
                .append("        try {\n")
                .append("            ByteArrayOutputStream bytes = new ByteArrayOutputStream();\n")
                .append("            try (DataOutputStream output = new DataOutputStream(bytes)) {\n")
                .append("                output.write(DOMAIN_SEPARATOR);\n")
                .append("                output.writeByte(ENCODING_VERSION);\n");

        for (ContractSchema.PolicyDigestField field : fields) {
            source.append("                ").append(writerMethod(field.type()))
                    .append("(output, ").append(field.id()).append(", ").append(field.name()).append(");\n");
        }

        source.append("            }\n")
                .append("            MessageDigest digest = MessageDigest.getInstance(")
                .append(quoted(contract.policyDigestAlgorithm())).append(");\n")
                .append("            return HexFormat.of().formatHex(digest.digest(bytes.toByteArray()));\n")
                .append("        } catch (IOException exception) {\n")
                .append("            throw new IllegalStateException(")
                .append(quoted("Cannot encode tracing policy digest")).append(", exception);\n")
                .append("        } catch (NoSuchAlgorithmException exception) {\n")
                .append("            throw new IllegalStateException(")
                .append(quoted("Policy digest algorithm is unavailable")).append(", exception);\n")
                .append("        }\n")
                .append("    }\n\n")
                .append("    private static void writeBoolean(DataOutputStream output, int fieldId, boolean value) throws IOException {\n")
                .append("        output.writeByte(fieldId);\n")
                .append("        output.writeByte(value ? 1 : 0);\n")
                .append("    }\n\n")
                .append("    private static void writeDouble(DataOutputStream output, int fieldId, double value) throws IOException {\n")
                .append("        requireRatio(value, ").append(quoted("ratio")).append(");\n")
                .append("        output.writeByte(fieldId);\n")
                .append("        output.writeLong(Double.doubleToLongBits(normalizeZero(value)));\n")
                .append("    }\n\n")
                .append("    private static void writeRouteRatios(\n")
                .append("            DataOutputStream output,\n")
                .append("            int fieldId,\n")
                .append("            Map<String, Double> routeRatios\n")
                .append("    ) throws IOException {\n")
                .append("        output.writeByte(fieldId);\n")
                .append("        List<EncodedRouteRatio> entries = new ArrayList<>(routeRatios.size());\n")
                .append("        for (Map.Entry<String, Double> entry : routeRatios.entrySet()) {\n")
                .append("            String routePrefix = Objects.requireNonNull(entry.getKey(), ")
                .append(quoted("routePrefix")).append(");\n")
                .append("            Double ratio = Objects.requireNonNull(entry.getValue(), ")
                .append(quoted("routeRatio")).append(");\n")
                .append("            byte[] encodedPrefix = routePrefix.getBytes(StandardCharsets.UTF_8);\n")
                .append("            if (encodedPrefix.length == 0 || encodedPrefix.length > MAX_ROUTE_PREFIX_UTF8_BYTES) {\n")
                .append("                throw new IllegalArgumentException(")
                .append(quoted("Sampling route prefix has invalid UTF-8 length")).append(");\n")
                .append("            }\n")
                .append("            requireRatio(ratio, ").append(quoted("routeRatio")).append(");\n")
                .append("            entries.add(new EncodedRouteRatio(encodedPrefix, normalizeZero(ratio)));\n")
                .append("        }\n")
                .append("        entries.sort(ROUTE_PREFIX_ORDER);\n")
                .append("        output.writeInt(entries.size());\n")
                .append("        for (EncodedRouteRatio entry : entries) {\n")
                .append("            output.writeInt(entry.routePrefix().length);\n")
                .append("            output.write(entry.routePrefix());\n")
                .append("            output.writeLong(Double.doubleToLongBits(entry.ratio()));\n")
                .append("        }\n")
                .append("    }\n\n")
                .append("    private static void writeAsciiToken(DataOutputStream output, int fieldId, String token) throws IOException {\n")
                .append("        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(token)) {\n")
                .append("            throw new IllegalArgumentException(")
                .append(quoted("Diagnostics log level must be an ASCII token")).append(");\n")
                .append("        }\n")
                .append("        output.writeByte(fieldId);\n")
                .append("        byte[] encoded = token.getBytes(StandardCharsets.US_ASCII);\n")
                .append("        output.writeInt(encoded.length);\n")
                .append("        output.write(encoded);\n")
                .append("    }\n\n")
                .append("    private static void requireRatio(double value, String name) {\n")
                .append("        if (!Double.isFinite(value) || value < 0.0d || value > 1.0d) {\n")
                .append("            throw new IllegalArgumentException(name + ")
                .append(quoted(" must be finite and between 0.0 and 1.0")).append(");\n")
                .append("        }\n")
                .append("    }\n\n")
                .append("    private static double normalizeZero(double value) {\n")
                .append("        return value == 0.0d ? 0.0d : value;\n")
                .append("    }\n\n")
                .append("    private static int compareUnsigned(byte[] left, byte[] right) {\n")
                .append("        int commonLength = Math.min(left.length, right.length);\n")
                .append("        for (int index = 0; index < commonLength; index++) {\n")
                .append("            int comparison = Integer.compare(Byte.toUnsignedInt(left[index]), Byte.toUnsignedInt(right[index]));\n")
                .append("            if (comparison != 0) {\n")
                .append("                return comparison;\n")
                .append("            }\n")
                .append("        }\n")
                .append("        return Integer.compare(left.length, right.length);\n")
                .append("    }\n\n")
                .append("    private record EncodedRouteRatio(byte[] routePrefix, double ratio) {\n")
                .append("    }\n")
                .append("}\n");

        return source.toString();
    }

    private static String javaParameterType(String policyDigestFieldType) {
        return switch (policyDigestFieldType) {
            case "boolean" -> "boolean";
            case "double" -> "double";
            case "sortedUtf8DoubleMap" -> "Map<String, Double>";
            case "asciiToken" -> "String";
            default -> throw new IllegalArgumentException("Unsupported policy digest field type: " + policyDigestFieldType);
        };
    }

    private static boolean requiresNonNullCheck(String policyDigestFieldType) {
        return switch (policyDigestFieldType) {
            case "boolean", "double" -> false;
            case "sortedUtf8DoubleMap", "asciiToken" -> true;
            default -> throw new IllegalArgumentException("Unsupported policy digest field type: " + policyDigestFieldType);
        };
    }

    private static String writerMethod(String policyDigestFieldType) {
        return switch (policyDigestFieldType) {
            case "boolean" -> "writeBoolean";
            case "double" -> "writeDouble";
            case "sortedUtf8DoubleMap" -> "writeRouteRatios";
            case "asciiToken" -> "writeAsciiToken";
            default -> throw new IllegalArgumentException("Unsupported policy digest field type: " + policyDigestFieldType);
        };
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

    private static void writeIdentity(ContractDocument contract, String plane, Path resourceRoot) throws IOException {
        String content = "descriptors.sha256=" + contract.descriptorDigest() + '\n'
                + "plane=" + plane + '\n'
                + "policyDigest.algorithm=" + contract.policyDigestAlgorithm() + '\n'
                + "policyDigest.version=" + contract.policyDigestVersion() + '\n'
                + "schema.id=" + contract.schemaId() + '\n'
                + "schema.sha256=" + contract.schemaDigest() + '\n';

        JmxWireContractGenerator.writeUtf8(
                resourceRoot.resolve("META-INF/platform-tracing/jmx-wire-contract.properties"),
                content
        );
    }

    private static String packageName(Map<String, Object> planes, String plane) {
        return (String) ContractValues.map(planes.get(plane), "planes." + plane).get("packageName");
    }

    private static String upperSnake(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isUpperCase(character) && index > 0
                    && result.charAt(result.length() - 1) != '_') {
                result.append('_');
            }

            result.append(
                    Character.isLetterOrDigit(character)
                    ? Character.toUpperCase(character)
                    : '_'
            );
        }

        return result.toString();
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
