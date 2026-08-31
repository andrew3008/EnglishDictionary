package space.br1440.platform.tracing.jmx.contract.tool.validator;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.jmx.contract.tool.ContractValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EndpointSectionValidatorTest {

    private static final Map<String, Object> OPEN_TYPES = Map.of(
            "value", Map.of("kind", "composite", "typeName", "Value", "fields",
                    List.of(
                            Map.of("name", "x", "type", "string", "required", true, "nullable", false)
                    )
            )
    );

    private static Map<String, Object> validMutation() {
        return Map.of(
                "objectName", "settingsMutation",
                "role", "mutation",
                "attributes", List.of(),
                "operations", List.of(Map.of(
                        "name", "applySnapshot",
                        "impact", "ACTION",
                        "returnJavaType", "void",
                        "returnOpenType", "value",
                        "parameters", List.of()
                ))
        );
    }

    private static ContractValidationContext context(Map<String, Object> endpoints) {
        Map<String, Object> root = Map.of("endpoints", endpoints);
        return new ContractValidationContext(root, Map.of(), Map.of("settingsMutation", "x"), OPEN_TYPES);
    }

    @Test
    void rejectsEndpointObjectNameMismatch() {
        Map<String, Object> endpoints = Map.of("settingsMutation", Map.of(
                "objectName", "wrongName",
                "role", "mutation",
                "attributes", List.of(),
                "operations", List.of()
        ));

        assertThatThrownBy(() -> EndpointSectionValidator.validate(context(endpoints)))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_ENDPOINT_OBJECT_NAME_INVALID"));
    }

    @Test
    void rejectsUnsupportedRole() {
        Map<String, Object> endpoints = Map.of("settingsMutation", Map.of(
                "objectName", "settingsMutation",
                "role", "unsupported-role",
                "attributes", List.of(),
                "operations", List.of()
        ));

        assertThatThrownBy(() -> EndpointSectionValidator.validate(context(endpoints)))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_ENDPOINT_ROLE_INVALID"));
    }

    @Test
    void rejectsMutationEndpointWithAttributes() {
        Map<String, Object> endpoints = Map.of("settingsMutation", Map.of(
                "objectName", "settingsMutation",
                "role", "mutation",
                "attributes", List.of(Map.of(
                        "name", "x", "javaType", "boolean", "openType", "value",
                        "readable", true, "writable", false, "required", true, "nullable", false)),
                "operations", validMutation().get("operations")
        ));

        assertThatThrownBy(() -> EndpointSectionValidator.validate(context(endpoints)))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_MUTATION_SURFACE_INVALID"));
    }

    @Test
    void rejectsDuplicateOperationName() {
        Map<String, Object> operation = Map.of(
                "name", "applySnapshot", "impact", "ACTION",
                "returnJavaType", "void", "returnOpenType", "value", "parameters", List.of());

        Map<String, Object> endpoints = Map.of("settingsMutation", Map.of(
                "objectName", "settingsMutation",
                "role", "mutation",
                "attributes", List.of(),
                "operations", List.of(operation, operation)
        ));

        assertThatThrownBy(() -> EndpointSectionValidator.validate(context(endpoints)))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_DUPLICATE_MEMBER"));
    }

    @Test
    void rejectsUnsupportedOperationImpact() {
        Map<String, Object> endpoints = Map.of("settingsMutation", Map.of(
                "objectName", "settingsMutation",
                "role", "mutation",
                "attributes", List.of(),
                "operations", List.of(Map.of(
                        "name", "applySnapshot", "impact", "UNSUPPORTED",
                        "returnJavaType", "void", "returnOpenType", "value", "parameters", List.of()))
        ));

        assertThatThrownBy(() -> EndpointSectionValidator.validate(context(endpoints)))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_OPERATION_IMPACT_INVALID"));
    }

    @Test
    void acceptsValidMutationEndpoint() {
        Map<String, Object> endpoints = Map.of("settingsMutation", validMutation());
        EndpointSectionValidator.validate(context(endpoints));
    }
}
