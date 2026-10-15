package space.br1440.platform.tracing.jmx.contract.tool;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import lombok.experimental.UtilityClass;

@UtilityClass
final class WireGraphWorstCase {

    private static final int CANONICAL_UUID_BYTES = 36;

    static void requireMinimalBudget(Map<String, Object> root) {
        long required = maximumLogicalBytes(root);
        int configured = ContractValues.integer(
                ContractValues.map(root.get("wireGraph"), "wireGraph").get("maxLogicalBytes"),
                "wireGraph.maxLogicalBytes"
        );

        if (configured != required) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_LOGICAL_BUDGET_INVALID",
                    "wireGraph.maxLogicalBytes must equal the minimum worst-case budget: " + required
            );
        }
    }

    static long maximumLogicalBytes(Map<String, Object> root) {
        long acceptedPolicySnapshot = compositeBytes(root, "acceptedPolicySnapshot");
        long applySnapshotRequest = compositeBytes(root, "applySnapshotRequest");
        return Math.max(acceptedPolicySnapshot, applySnapshotRequest);
    }

    private static long compositeBytes(Map<String, Object> root, String typeId) {
        Map<String, Object> descriptor = openType(root, typeId);
        long bytes = utf8Bytes(ContractValues.string(
                descriptor.get("typeName"),
                "openTypes." + typeId + ".typeName"
        ));

        List<Object> fields = ContractValues.list(
                descriptor.get("fields"),
                "openTypes." + typeId + ".fields"
        );

        for (Object rawField : fields) {
            Map<String, Object> field = ContractValues.map(rawField, "openTypes." + typeId + ".field");
            String fieldName = ContractValues.string(field.get("name"), "openType.field.name");
            String fieldType = ContractValues.string(field.get("type"), "openType.field.type");
            bytes = Math.addExact(bytes, utf8Bytes(fieldName));
            bytes = Math.addExact(bytes, valueBytes(root, fieldName, fieldType));
        }

        return bytes;
    }

    private static long valueBytes(Map<String, Object> root, String fieldName, String fieldType) {
        return switch (fieldType) {
            case "boolean" -> Byte.BYTES;
            case "int", "float" -> Integer.BYTES;
            case "long", "double" -> Long.BYTES;
            case "string" -> maximumStringBytes(root, fieldName);
            default -> nestedBytes(root, fieldType);
        };
    }

    private static long nestedBytes(Map<String, Object> root, String fieldType) {
        int separator = fieldType.indexOf(':');
        if (separator < 0) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_LOGICAL_BUDGET_INVALID",
                    "Unsupported worst-case field type: " + fieldType
            );
        }

        String kind = fieldType.substring(0, separator);
        String typeId = fieldType.substring(separator + 1);
        return switch (kind) {
            case "composite" -> compositeBytes(root, typeId);
            case "tabular" -> tabularBytes(root, typeId);
            default -> throw new ContractValidationException(
                    "JMX_SCHEMA_LOGICAL_BUDGET_INVALID",
                    "Unsupported worst-case nested field type: " + fieldType
            );
        };
    }

    private static long tabularBytes(Map<String, Object> root, String typeId) {
        Map<String, Object> descriptor = openType(root, typeId);
        long bytes = utf8Bytes(ContractValues.string(
                descriptor.get("typeName"),
                "openTypes." + typeId + ".typeName"
        ));

        List<String> indexFields = ContractValues.strings(
                descriptor.get("indexFields"),
                "openTypes." + typeId + ".indexFields"
        );
        for (String indexField : indexFields) {
            bytes = Math.addExact(bytes, utf8Bytes(indexField));
        }

        if (!"routeRatios".equals(typeId)) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_LOGICAL_BUDGET_INVALID",
                    "Unsupported worst-case tabular type: " + typeId
            );
        }

        int routeCount = wireGraphInt(root, "maxRouteEntries");
        String rowType = ContractValues.string(
                descriptor.get("rowType"),
                "openTypes." + typeId + ".rowType"
        );

        return Math.addExact(
                bytes,
                Math.multiplyExact(routeCount, compositeBytes(root, rowType))
        );
    }

    private static long maximumStringBytes(Map<String, Object> root, String fieldName) {
        return switch (fieldName) {
            case "attemptId", "agentInstanceId", "expectedAgentInstanceId" -> CANONICAL_UUID_BYTES;
            case "routePrefix" -> wireGraphInt(root, "maxRoutePrefixUtf8Bytes");
            case "writer" -> maximumTokenBytes(root, "writer");
            case "sourceAuthority" -> maximumTokenBytes(root, "sourceAuthority");
            case "outcome" -> maximumTokenBytes(root, "outcome");
            case "messageCode" -> maximumTokenBytes(root, "messageCode");
            case "diagnosticsLogLevel" -> maximumTokenBytes(root, "logLevel");
            case "validationLevel" -> maximumTokenBytes(root, "validationLevel");
            default -> wireGraphInt(root, "maxStringUtf8Bytes");
        };
    }

    private static long maximumTokenBytes(Map<String, Object> root, String sectionName) {
        Map<String, Object> section = ContractValues.map(root.get(sectionName), sectionName);
        return maximumBytes(ContractValues.strings(section.get("values"), sectionName + ".values"));
    }

    private static long maximumBytes(List<String> values) {
        return values.stream()
                .mapToLong(WireGraphWorstCase::utf8Bytes)
                .max()
                .orElseThrow(() -> new ContractValidationException(
                        "JMX_SCHEMA_LOGICAL_BUDGET_INVALID",
                        "Token set must not be empty"
                ));
    }

    private static Map<String, Object> openType(Map<String, Object> root, String typeId) {
        Map<String, Object> openTypes = ContractValues.map(root.get("openTypes"), "openTypes");
        return ContractValues.map(openTypes.get(typeId), "openTypes." + typeId);
    }

    private static int wireGraphInt(Map<String, Object> root, String key) {
        Map<String, Object> wireGraph = ContractValues.map(root.get("wireGraph"), "wireGraph");
        return ContractValues.integer(wireGraph.get(key), "wireGraph." + key);
    }

    private static long utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
