package space.br1440.platform.tracing.jmx.contract.tool.validator;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import space.br1440.platform.tracing.jmx.contract.tool.ContractValidationException;
import space.br1440.platform.tracing.jmx.contract.tool.ContractValues;

import lombok.experimental.UtilityClass;

@UtilityClass
final class ReadinessEvidenceSectionValidator {

    static void validate(ContractValidationContext context) {
        Map<String, Object> readinessEvidence = context.section("readinessEvidence");
        Map<String, Object> openTypes = context.openTypes();
        Set<String> keys = Set.of("distributionFields", "topologyFields");
        ContractValues.assertKeys(readinessEvidence, "readinessEvidence", keys, keys);

        Set<String> evidenceFields = new LinkedHashSet<>(
                ContractValues.strings(
                        readinessEvidence.get("distributionFields"),
                        "readinessEvidence.distributionFields"
                )
        );

        List<String> topologyFields = ContractValues.strings(
                readinessEvidence.get("topologyFields"),
                "readinessEvidence.topologyFields"
        );
        evidenceFields.addAll(topologyFields);

        if (evidenceFields.size() < 2) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_READINESS_EVIDENCE_INVALID",
                    "Readiness evidence field lists must be unique and non-empty"
            );
        }

        if (topologyFields.size() < 2) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_READINESS_EVIDENCE_INVALID",
                    "Topology evidence must describe both the extension and the SDK defining loader"
            );
        }

        Map<String, Object> readiness = ContractValues.map(
                openTypes.get("readinessSnapshot"),
                "openTypes.readinessSnapshot"
        );

        Set<String> readinessFields = OpenTypeSectionValidator.fieldNames(readiness, "openTypes.readinessSnapshot");
        if (!readinessFields.containsAll(evidenceFields)) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_READINESS_EVIDENCE_INVALID",
                    "Readiness snapshot must contain every readiness evidence field"
            );
        }
    }
}
