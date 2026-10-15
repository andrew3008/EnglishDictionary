package space.br1440.platform.tracing.jmx.contract.tool.validator;

import java.util.List;
import java.util.Map;
import java.util.Set;

import space.br1440.platform.tracing.jmx.contract.tool.ContractSchema;
import space.br1440.platform.tracing.jmx.contract.tool.ContractValidationException;
import space.br1440.platform.tracing.jmx.contract.tool.ContractValues;

import lombok.experimental.UtilityClass;

@UtilityClass
final class PolicyDigestSectionValidator {

    static void validate(ContractValidationContext context) {
        Map<String, Object> digest = context.section("policyDigest");
        Set<String> keys = Set.of(
                "algorithmId",
                "algorithmVersion",
                "encodingVersion",
                "domainSeparator",
                "routeOrdering",
                "fields"
        );

        ContractValues.assertKeys(digest, "policyDigest", keys, keys);
        ContractValues.requireValue(digest, "algorithmId", "SHA-256", "policyDigest");
        if (ContractValues.integer(digest.get("algorithmVersion"), "policyDigest.algorithmVersion") != 1
                || ContractValues.integer(digest.get("encodingVersion"), "policyDigest.encodingVersion") != 1) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_DIGEST_VERSION_INVALID",
                    "Unsupported policy digest version"
            );
        }

        ContractValues.string(digest.get("domainSeparator"), "policyDigest.domainSeparator");
        ContractValues.requireValue(digest, "routeOrdering", "unsigned-utf8-lexicographic", "policyDigest");

        validateFields(digest);
    }

    private static void validateFields(Map<String, Object> digest) {
        List<Object> fields = ContractValues.list(digest.get("fields"), "policyDigest.fields");
        if (fields.size() != ContractSchema.POLICY_DIGEST_FIELDS.size()) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_DIGEST_FIELD_INVALID",
                    "Policy digest must define " + ContractSchema.POLICY_DIGEST_FIELDS.size() + " fields"
            );
        }

        for (int index = 0; index < fields.size(); index++) {
            String path = "policyDigest.fields[" + index + ']';
            Map<String, Object> field = ContractValues.map(fields.get(index), path);
            Set<String> fieldKeys = Set.of("id", "name", "type");
            ContractValues.assertKeys(field, path, fieldKeys, fieldKeys);

            ContractSchema.PolicyDigestField expected = ContractSchema.POLICY_DIGEST_FIELDS.get(index);
            if (ContractValues.integer(field.get("id"), path + ".id") != expected.id()
                    || !expected.name().equals(field.get("name"))
                    || !expected.type().equals(field.get("type"))) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_DIGEST_FIELD_INVALID",
                        "Policy digest field changed at index " + index
                );
            }
        }
    }
}
