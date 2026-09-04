package space.br1440.platform.tracing.jmx.contract.tool.validator;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.jmx.contract.tool.ContractValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenTypeSectionValidatorTest {

    private static ContractValidationContext context(Map<String, Object> openTypes) {
        return new ContractValidationContext(Map.of(), Map.of(), Map.of(), openTypes);
    }

    @Test
    void rejectsUnsupportedKind() {
        Map<String, Object> openTypes = Map.of(
                "example", Map.of("kind", "unsupported", "typeName", "Example")
        );

        assertThatThrownBy(() -> OpenTypeSectionValidator.validate(context(openTypes)))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_OPEN_TYPE_KIND_INVALID"));
    }

    @Test
    void rejectsUnknownOpenTypeReference() {
        Map<String, Object> openTypes = Map.of(
                "example", Map.of(
                        "kind", "composite",
                        "typeName", "Example",
                        "fields", List.of(Map.of(
                                "name", "value",
                                "type", "composite:missing",
                                "required", true,
                                "nullable", false
                        ))
                )
        );

        assertThatThrownBy(() -> OpenTypeSectionValidator.validate(context(openTypes)))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_OPEN_TYPE_REFERENCE_INVALID"));
    }

    @Test
    void rejectsDuplicateTypeName() {
        Map<String, Object> openTypes = Map.of(
                "first", Map.of("kind", "composite", "typeName", "Same", "fields", List.of(
                        Map.of("name", "a", "type", "string", "required", true, "nullable", false))),

                "second", Map.of("kind", "composite", "typeName", "Same", "fields", List.of(
                        Map.of("name", "b", "type", "string", "required", true, "nullable", false)))
        );

        assertThatThrownBy(() -> OpenTypeSectionValidator.validate(context(openTypes)))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_OPEN_TYPE_NAME_COLLISION"));
    }

    @Test
    void rejectsOpenTypeDependencyCycle() {
        Map<String, Object> openTypes = Map.of(
                "self", Map.of(
                        "kind", "composite",
                        "typeName", "Self",
                        "fields", List.of(Map.of(
                                "name", "value",
                                "type", "composite:self",
                                "required", true,
                                "nullable", false
                        ))
                )
        );

        assertThatThrownBy(() -> OpenTypeSectionValidator.validate(context(openTypes)))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_OPEN_TYPE_CYCLE"));
    }

    @Test
    void rejectsTabularIndexOutsideRowFields() {
        Map<String, Object> openTypes = Map.of(
                "row", Map.of("kind", "composite", "typeName", "Row", "fields", List.of(
                        Map.of("name", "id", "type", "string", "required", true, "nullable", false))),

                "table", Map.of(
                        "kind", "tabular", "typeName", "Table",
                        "rowType", "row", "indexFields", List.of("missing")
                )
        );

        assertThatThrownBy(() -> OpenTypeSectionValidator.validate(context(openTypes)))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_TABULAR_INDEX_INVALID"));
    }
}
