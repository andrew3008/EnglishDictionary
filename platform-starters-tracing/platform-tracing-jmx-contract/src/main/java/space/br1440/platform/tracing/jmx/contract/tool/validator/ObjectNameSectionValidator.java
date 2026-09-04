package space.br1440.platform.tracing.jmx.contract.tool.validator;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import space.br1440.platform.tracing.jmx.contract.tool.ContractValidationException;
import space.br1440.platform.tracing.jmx.contract.tool.ContractValues;

import lombok.experimental.UtilityClass;

@UtilityClass
final class ObjectNameSectionValidator {

    private static final Set<String> REQUIRED = Set.of(
            "settingsMutation",
            "settingsStatus",
            "extensionReadiness",
            "samplingObservation",
            "scrubbingObservation",
            "validationObservation",
            "exportObservation",
            "processorMetricsObservation",
            "diagnosticsObservation"
    );

    static void validate(ContractValidationContext context) {
        Map<String, Object> objectNames = context.objectNames();
        ContractValues.assertKeys(objectNames, "objectNames", REQUIRED, REQUIRED);

        Set<ObjectName> unique = new HashSet<>();
        for (Map.Entry<String, Object> entry : objectNames.entrySet()) {
            try {
                ObjectName objectName = ObjectName.getInstance(
                        ContractValues.string(
                                entry.getValue(),
                                "objectNames." + entry.getKey()
                        )
                );

                if (!unique.add(objectName)) {
                    throw new ContractValidationException(
                            "JMX_SCHEMA_OBJECT_NAME_COLLISION",
                            "ObjectName values must be unique"
                    );
                }
            } catch (MalformedObjectNameException exception) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_INVALID_OBJECT_NAME",
                        "Invalid ObjectName at objectNames." + entry.getKey()
                );
            }
        }
    }
}
