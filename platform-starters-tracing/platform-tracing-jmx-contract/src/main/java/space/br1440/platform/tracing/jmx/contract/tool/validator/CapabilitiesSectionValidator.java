package space.br1440.platform.tracing.jmx.contract.tool.validator;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import space.br1440.platform.tracing.jmx.contract.tool.ContractValidationException;
import space.br1440.platform.tracing.jmx.contract.tool.ContractValues;

import lombok.experimental.UtilityClass;

@UtilityClass
final class CapabilitiesSectionValidator {

    static void validate(ContractValidationContext context) {
        Map<String, Object> capabilities = context.section("capabilities");
        Set<String> keys = Set.of("required");
        ContractValues.assertKeys(capabilities, "capabilities", keys, keys);

        List<String> required = ContractValues.strings(capabilities.get("required"), "capabilities.required");
        if (required.isEmpty() || new HashSet<>(required).size() != required.size()) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_CAPABILITIES_INVALID",
                    "Required capabilities must be non-empty and unique"
            );
        }

        for (String capability : required) {
            if (!capability.matches("[A-Z][A-Z0-9_]*")) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_CAPABILITIES_INVALID",
                        "Invalid capability identifier: " + capability
                );
            }
        }
    }
}
