package space.br1440.platform.tracing.jmx.contract.tool.validator;

import java.util.Map;
import java.util.Set;

import space.br1440.platform.tracing.jmx.contract.tool.ContractValues;

import lombok.experimental.UtilityClass;

@UtilityClass
final class SchemaSectionValidator {

    static void validate(ContractValidationContext context) {
        Map<String, Object> schema = context.schema();
        Set<String> schemaKeys = Set.of("id", "canonicalization", "compatibility");
        ContractValues.assertKeys(schema, "schema", schemaKeys, schemaKeys);
        ContractValues.string(schema.get("id"), "schema.id");

        Map<String, Object> canonicalization = ContractValues.map(
                schema.get("canonicalization"),
                "schema.canonicalization"
        );

        Set<String> canonicalizationKeys = Set.of(
                "format", "unicodeNormalization", "mapKeyOrdering", "sequenceOrdering", "lineEnding"
        );
        ContractValues.assertKeys(canonicalization, "schema.canonicalization", canonicalizationKeys, canonicalizationKeys);

        ContractValues.requireValue(canonicalization, "format", "canonical-tree", "schema.canonicalization");
        ContractValues.requireValue(canonicalization, "unicodeNormalization", "NFC", "schema.canonicalization");
        ContractValues.requireValue(canonicalization, "mapKeyOrdering", "unicode-code-point", "schema.canonicalization");
        ContractValues.requireValue(canonicalization, "sequenceOrdering", "declaration-order", "schema.canonicalization");
        ContractValues.requireValue(canonicalization, "lineEnding", "LF", "schema.canonicalization");

        Map<String, Object> compatibility = ContractValues.map(schema.get("compatibility"), "schema.compatibility");
        Set<String> compatibilityKeys = Set.of("unknownFieldPolicy", "mismatchLifecycle");
        ContractValues.assertKeys(compatibility, "schema.compatibility", compatibilityKeys, compatibilityKeys);
        ContractValues.requireValue(compatibility, "unknownFieldPolicy", "reject", "schema.compatibility");
        ContractValues.requireValue(compatibility, "mismatchLifecycle", "sticky-failed", "schema.compatibility");
    }
}
