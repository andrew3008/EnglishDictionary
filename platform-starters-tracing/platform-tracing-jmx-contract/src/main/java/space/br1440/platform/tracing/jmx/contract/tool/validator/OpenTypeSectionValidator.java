package space.br1440.platform.tracing.jmx.contract.tool.validator;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import space.br1440.platform.tracing.jmx.contract.tool.ContractValidationException;
import space.br1440.platform.tracing.jmx.contract.tool.ContractValues;

import lombok.experimental.UtilityClass;

@UtilityClass
final class OpenTypeSectionValidator {

    private static final Set<String> SIMPLE_OPEN_TYPES = Set.of(
            "boolean", "int", "long", "double", "string", "stringArray", "objectName"
    );

    static void validate(ContractValidationContext context) {
        Map<String, Object> openTypes = context.openTypes();
        if (openTypes.isEmpty()) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_REQUIRED_FIELD_MISSING",
                    "openTypes must not be empty"
            );
        }

        Set<String> typeNames = new HashSet<>();
        for (Map.Entry<String, Object> entry : openTypes.entrySet()) {
            String path = "openTypes." + entry.getKey();
            Map<String, Object> descriptor = ContractValues.map(entry.getValue(), path);

            String kind = ContractValues.string(descriptor.get("kind"), path + ".kind");
            switch (kind) {
                case "composite" -> validateComposite(descriptor, path, openTypes, typeNames);
                case "tabular" -> validateTabular(descriptor, path, openTypes, typeNames);
                case "map" -> validateMap(descriptor, path, typeNames);
                default -> throw new ContractValidationException(
                        "JMX_SCHEMA_OPEN_TYPE_KIND_INVALID",
                        "Unsupported Open Type kind: " + kind
                );
            }
        }

        assertAcyclic(openTypes);
    }

    static void requireOpenType(Map<String, Object> openTypes, Object value, String path) {
        String type = ContractValues.string(value, path);
        if (!openTypes.containsKey(type)) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_OPEN_TYPE_REFERENCE_INVALID",
                    path + " references unknown Open Type " + type
            );
        }
    }

    static Set<String> fieldNames(Map<String, Object> composite, String path) {
        Set<String> result = new LinkedHashSet<>();
        for (Object rawField : ContractValues.list(composite.get("fields"), path + ".fields")) {
            Map<String, Object> field = ContractValues.map(rawField, path + ".field");
            result.add(ContractValues.string(field.get("name"), path + ".field.name"));
        }

        return result;
    }

    private static void validateComposite(Map<String, Object> descriptor,
                                          String path, Map<String, Object> openTypes,
                                          Set<String> typeNames) {
        Set<String> keys = Set.of("kind", "typeName", "fields");
        ContractValues.assertKeys(descriptor, path, keys, keys);
        addTypeName(descriptor, path, typeNames);

        List<Object> fields = ContractValues.list(descriptor.get("fields"), path + ".fields");
        if (fields.isEmpty()) {
            throw new ContractValidationException("JMX_SCHEMA_REQUIRED_FIELD_MISSING", path + ".fields must not be empty");
        }

        Set<String> names = new HashSet<>();
        for (int index = 0; index < fields.size(); index++) {
            String fieldPath = path + ".fields[" + index + ']';
            Map<String, Object> field = ContractValues.map(fields.get(index), fieldPath);
            Set<String> fieldKeys = Set.of("name", "type", "required", "nullable");
            ContractValues.assertKeys(field, fieldPath, fieldKeys, fieldKeys);

            if (!names.add(ContractValues.string(field.get("name"), fieldPath + ".name"))) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_DUPLICATE_MEMBER", "Duplicate Open Type field at " + path);
            }

            validateTypeReference(ContractValues.string(field.get("type"), fieldPath + ".type"), openTypes, fieldPath);
            ContractValues.bool(field.get("required"), fieldPath + ".required");
            ContractValues.bool(field.get("nullable"), fieldPath + ".nullable");
        }
    }

    private static void validateTabular(Map<String, Object> descriptor,
                                        String path, Map<String, Object> openTypes,
                                        Set<String> typeNames) {
        Set<String> keys = Set.of("kind", "typeName", "rowType", "indexFields");
        ContractValues.assertKeys(descriptor, path, keys, keys);
        addTypeName(descriptor, path, typeNames);

        String rowType = ContractValues.string(descriptor.get("rowType"), path + ".rowType");
        Map<String, Object> row = ContractValues.map(openTypes.get(rowType), "openTypes." + rowType);
        if (!"composite".equals(row.get("kind"))) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_OPEN_TYPE_REFERENCE_INVALID",
                    path + ".rowType must reference a composite type"
            );
        }

        Set<String> rowFields = fieldNames(row, "openTypes." + rowType);
        List<String> indexFields = ContractValues.strings(descriptor.get("indexFields"), path + ".indexFields");
        if (indexFields.isEmpty() || !rowFields.containsAll(indexFields)) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_TABULAR_INDEX_INVALID",
                    path + ".indexFields must reference row fields"
            );
        }
    }

    private static void validateMap(Map<String, Object> descriptor, String path, Set<String> typeNames) {
        Set<String> requiredKeys = Set.of(
                "kind", "typeName", "rowTypeName", "keyField", "keyType",
                "valueField", "valueType", "indexFields"
        );
        Set<String> allowedKeys = new HashSet<>(requiredKeys);
        allowedKeys.add("keys");
        ContractValues.assertKeys(descriptor, path, allowedKeys, requiredKeys);
        addTypeName(descriptor, path, typeNames);

        if (!typeNames.add(ContractValues.string(descriptor.get("rowTypeName"), path + ".rowTypeName"))) {
            throw new ContractValidationException("JMX_SCHEMA_OPEN_TYPE_NAME_COLLISION", "Open Type names must be unique");
        }

        String keyField = ContractValues.string(descriptor.get("keyField"), path + ".keyField");
        ContractValues.string(descriptor.get("valueField"), path + ".valueField");
        if (!SIMPLE_OPEN_TYPES.contains(ContractValues.string(descriptor.get("keyType"), path + ".keyType"))
                || !SIMPLE_OPEN_TYPES.contains(ContractValues.string(descriptor.get("valueType"), path + ".valueType"))) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_OPEN_TYPE_REFERENCE_INVALID",
                    path + " map types must be simple Open Types"
            );
        }

        List<String> indexes = ContractValues.strings(descriptor.get("indexFields"), path + ".indexFields");
        if (!indexes.equals(List.of(keyField))) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_TABULAR_INDEX_INVALID",
                    path + " must index the key field exactly"
            );
        }

        if (descriptor.containsKey("keys")) {
            validateMapKeys(descriptor.get("keys"), path + ".keys");
        }
    }

    private static void validateMapKeys(Object value, String path) {
        Set<String> wireKeys = new HashSet<>();
        Set<String> constantNames = new HashSet<>();
        for (String wireKey : ContractValues.strings(value, path)) {
            if (!wireKeys.add(wireKey)) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_DUPLICATE_MAP_KEY",
                        path + " contains duplicate map key: " + wireKey
                );
            }

            String constantName = ContractValues.javaConstantName(wireKey);
            if (!constantNames.add(constantName)) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_MAP_KEY_CONSTANT_COLLISION",
                        path + " contains keys with the same generated constant name: " + constantName
                );
            }
        }
    }

    private static void validateTypeReference(String type, Map<String, Object> openTypes, String path) {
        if (SIMPLE_OPEN_TYPES.contains(type)) {
            return;
        }

        String reference = referencedType(type);
        if (reference == null || !openTypes.containsKey(reference)) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_OPEN_TYPE_REFERENCE_INVALID",
                    path + " references unknown Open Type " + type
            );
        }

        String expectedKind = type.substring(0, type.indexOf(':'));
        String actualKind = ContractValues.string(
                ContractValues.map(openTypes.get(reference), "openTypes." + reference).get("kind"),
                "openTypes." + reference + ".kind"
        );

        boolean compositeMatch = "composite".equals(expectedKind) && "composite".equals(actualKind);
        boolean tabularMatch = "tabular".equals(expectedKind) && ("tabular".equals(actualKind) || "map".equals(actualKind));
        if (!compositeMatch && !tabularMatch) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_OPEN_TYPE_REFERENCE_INVALID",
                    path + " has incompatible Open Type kind"
            );
        }
    }

    private static String referencedType(String type) {
        int separator = type.indexOf(':');
        return (separator < 0) ? null : type.substring(separator + 1);
    }

    private static void addTypeName(Map<String, Object> descriptor, String path, Set<String> typeNames) {
        String typeName = ContractValues.string(descriptor.get("typeName"), path + ".typeName");
        if (!typeNames.add(typeName)) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_OPEN_TYPE_NAME_COLLISION",
                    "Open Type names must be unique: " + typeName
            );
        }
    }

    private static void assertAcyclic(Map<String, Object> openTypes) {
        Set<String> complete = new HashSet<>();
        Deque<String> visiting = new ArrayDeque<>();
        for (String type : openTypes.keySet()) {
            visit(type, openTypes, complete, visiting);
        }
    }

    private static void visit(String type,
                              Map<String, Object> openTypes,
                              Set<String> complete,
                              Deque<String> visiting) {
        if (complete.contains(type)) {
            return;
        }

        if (visiting.contains(type)) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_OPEN_TYPE_CYCLE",
                    "Open Type dependency cycle: " + visiting + " -> " + type
            );
        }
        visiting.addLast(type);

        Map<String, Object> descriptor = ContractValues.map(openTypes.get(type), "openTypes." + type);
        String kind = ContractValues.string(descriptor.get("kind"), "openTypes." + type + ".kind");
        if ("composite".equals(kind)) {
            for (Object rawField : ContractValues.list(descriptor.get("fields"), "openTypes." + type + ".fields")) {
                String reference = referencedType(ContractValues.string(
                        ContractValues.map(rawField, "openTypes." + type + ".field").get("type"),
                        "openTypes." + type + ".field.type"
                ));

                if (reference != null) {
                    visit(reference, openTypes, complete, visiting);
                }
            }
        } else if ("tabular".equals(kind)) {
            visit(
                    ContractValues.string(descriptor.get("rowType"), "openTypes." + type + ".rowType"),
                    openTypes,
                    complete,
                    visiting
            );
        }

        visiting.removeLast();
        complete.add(type);
    }
}
