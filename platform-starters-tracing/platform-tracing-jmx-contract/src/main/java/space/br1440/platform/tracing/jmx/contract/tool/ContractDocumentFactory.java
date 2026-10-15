package space.br1440.platform.tracing.jmx.contract.tool;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import space.br1440.platform.tracing.jmx.contract.tool.validator.ContractSectionValidators;

import lombok.experimental.UtilityClass;

@UtilityClass
final class ContractDocumentFactory {

    private static final Set<String> TOP_LEVEL_KEYS = Set.of(
            "schema", "planes", "objectNames", "endpoints",
            "openTypes", "capabilities", "readinessEvidence", "wireGraph",
            "writer", "sourceAuthority", "outcome", "messageCode", "logLevel",
            "validationLevel", "scannerState", "processorLifecycle", "readinessLifecycle"
    );

    static ContractDocument load(Path path) throws IOException {
        Map<String, Object> jmxContract = YamlDocumentLoader.load(path, "JMX contract");
        return validateAndCreate(jmxContract);
    }

    private static ContractDocument validateAndCreate(Map<String, Object> root) {
        ContractValues.assertKeys(root, "contract", TOP_LEVEL_KEYS, TOP_LEVEL_KEYS);

        Map<String, Object> schema = ContractValues.map(root.get("schema"), "schema");
        Map<String, Object> objectNames = ContractValues.map(root.get("objectNames"), "objectNames");
        Map<String, Object> openTypes = ContractValues.map(root.get("openTypes"), "openTypes");
        ContractSectionValidators.validateAll(root, schema, objectNames, openTypes);
        WireGraphWorstCase.requireMinimalBudget(root);

        Map<String, Object> descriptorSurface = new LinkedHashMap<>();
        descriptorSurface.put("objectNames", root.get("objectNames"));
        descriptorSurface.put("endpoints", root.get("endpoints"));
        descriptorSurface.put("openTypes", root.get("openTypes"));

        return new ContractDocument(
                root,
                ContractValues.string(schema.get("id"), "schema.id"),
                ContractCanonicalizer.sha256(ContractCanonicalizer.canonical(root)),
                ContractCanonicalizer.sha256(ContractCanonicalizer.canonical(descriptorSurface))
        );
    }
}
