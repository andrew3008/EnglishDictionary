package space.br1440.platform.tracing.jmx.contract.tool.validator;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import space.br1440.platform.tracing.jmx.contract.tool.ContractValidationException;
import space.br1440.platform.tracing.jmx.contract.tool.ContractValues;

import lombok.experimental.UtilityClass;

@UtilityClass
final class TokenSectionValidator {

    private static final List<String> SECTION_NAMES = List.of(
            "writer",
            "sourceAuthority",
            "outcome",
            "messageCode",
            "logLevel",
            "validationLevel",
            "scannerState",
            "processorLifecycle",
            "readinessLifecycle"
    );
    private static final Pattern TOKEN = Pattern.compile("[A-Z][A-Z0-9_]*");

    static void validate(ContractValidationContext context) {
        for (String sectionName : SECTION_NAMES) {
            validateSection(context.section(sectionName), sectionName);
        }
    }

    private static void validateSection(Map<String, Object> section, String sectionName) {
        ContractValues.assertKeys(section, sectionName, Set.of("values"), Set.of("values"));

        List<String> values = ContractValues.strings(section.get("values"), sectionName + ".values");
        if (values.isEmpty()
                || values.stream().anyMatch(value -> !TOKEN.matcher(value).matches())
                || new HashSet<>(values).size() != values.size()) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_TOKEN_SET_INVALID",
                    sectionName + ".values must contain unique uppercase tokens"
            );
        }
    }
}
