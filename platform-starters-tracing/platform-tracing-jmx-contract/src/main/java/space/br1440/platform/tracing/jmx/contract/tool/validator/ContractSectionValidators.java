package space.br1440.platform.tracing.jmx.contract.tool.validator;

import java.util.Map;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class ContractSectionValidators {

    public static void validateAll(Map<String, Object> root,
                                   Map<String, Object> schema,
                                   Map<String, Object> objectNames,
                                   Map<String, Object> openTypes) {
        ContractValidationContext context = new ContractValidationContext(root, schema, objectNames, openTypes);

        SchemaSectionValidator.validate(context);
        PlaneSectionValidator.validate(context);
        ObjectNameSectionValidator.validate(context);
        OpenTypeSectionValidator.validate(context);
        EndpointSectionValidator.validate(context);
        CapabilitiesSectionValidator.validate(context);
        ReadinessEvidenceSectionValidator.validate(context);
        WireGraphSectionValidator.validate(context);
        TokenSectionValidator.validate(context);
    }
}
