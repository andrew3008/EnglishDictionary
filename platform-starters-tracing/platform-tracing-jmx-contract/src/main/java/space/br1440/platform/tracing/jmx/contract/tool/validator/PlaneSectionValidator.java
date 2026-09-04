package space.br1440.platform.tracing.jmx.contract.tool.validator;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import space.br1440.platform.tracing.jmx.contract.tool.ContractValidationException;
import space.br1440.platform.tracing.jmx.contract.tool.ContractValues;

import lombok.experimental.UtilityClass;

@UtilityClass
final class PlaneSectionValidator {

    static void validate(ContractValidationContext context) {
        Map<String, Object> planes = context.section("planes");
        Set<String> planeIds = Set.of("extension", "application");
        ContractValues.assertKeys(planes, "planes", planeIds, planeIds);

        Set<String> packages = new HashSet<>();
        for (String plane : List.of("extension", "application")) {
            Map<String, Object> configuration = ContractValues.map(planes.get(plane), "planes." + plane);
            Set<String> keys = Set.of("packageName");
            ContractValues.assertKeys(configuration, "planes." + plane, keys, keys);

            String packageName = ContractValues.string(
                    configuration.get("packageName"),
                    "planes." + plane + ".packageName"
            );

            if (!packageName.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_INVALID_PACKAGE",
                        "Invalid generated package: " + packageName
                );
            }

            if (!packages.add(packageName)) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_PLANE_IDENTITY_COLLISION",
                        "Generated plane packages must differ"
                );
            }
        }
    }
}
