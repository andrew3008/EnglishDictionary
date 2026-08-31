package space.br1440.platform.tracing.jmx.contract.tool.validator;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import space.br1440.platform.tracing.jmx.contract.tool.ContractValidationException;
import space.br1440.platform.tracing.jmx.contract.tool.ContractValues;

import lombok.experimental.UtilityClass;

@UtilityClass
final class WireGraphSectionValidator {

    static void validate(ContractValidationContext context) {
        Map<String, Object> wireGraph = context.section("wireGraph");
        Set<String> keys = Set.of(
                "maxDepth",
                "maxElements",
                "maxLogicalBytes",
                "maxStringUtf8Bytes",
                "maxRouteEntries",
                "maxRoutePrefixUtf8Bytes",
                "maxCapabilityEntries",
                "allowedNeutralTypes",
                "allowedArrayComponents"
        );
        ContractValues.assertKeys(wireGraph, "wireGraph", keys, keys);

        for (String key : keys) {
            if (key.startsWith("max") && ContractValues.integer(wireGraph.get(key), "wireGraph." + key) <= 0) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_WIRE_BOUND_INVALID",
                        "wireGraph." + key + " must be positive"
                );
            }
        }

        List<String> neutral = ContractValues.strings(wireGraph.get("allowedNeutralTypes"), "wireGraph.allowedNeutralTypes");
        List<String> arrays = ContractValues.strings(wireGraph.get("allowedArrayComponents"), "wireGraph.allowedArrayComponents");
        if (neutral.isEmpty() || arrays.isEmpty()
                || new HashSet<>(neutral).size() != neutral.size()
                || new HashSet<>(arrays).size() != arrays.size()) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_WIRE_ALLOWLIST_INVALID",
                    "Wire graph allowlists must be non-empty and unique"
            );
        }
    }
}
