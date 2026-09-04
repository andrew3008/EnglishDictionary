package space.br1440.platform.tracing.jmx.contract.tool.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.jmx.contract.tool.ContractValidationException;

class PlaneSectionValidatorTest {

    private static ContractValidationContext context(Map<String, Object> planes) {
        Map<String, Object> root = Map.of("planes", planes);
        return new ContractValidationContext(root, Map.of(), Map.of(), Map.of());
    }

    @Test
    void rejectsInvalidPackageName() {
        Map<String, Object> planes = Map.of(
                "extension", Map.of("packageName", "Invalid-Package"),
                "application", Map.of("packageName", "space.br1440.app")
        );

        assertThatThrownBy(() -> PlaneSectionValidator.validate(context(planes)))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_INVALID_PACKAGE"));
    }

    @Test
    void rejectsIdenticalPlanePackages() {
        Map<String, Object> planes = Map.of(
                "extension", Map.of("packageName", "space.br1440.same"),
                "application", Map.of("packageName", "space.br1440.same")
        );

        assertThatThrownBy(() -> PlaneSectionValidator.validate(context(planes)))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_PLANE_IDENTITY_COLLISION"));
    }

    @Test
    void acceptsDistinctValidPackages() {
        Map<String, Object> planes = Map.of(
                "extension", Map.of("packageName", "space.br1440.extension"),
                "application", Map.of("packageName", "space.br1440.application")
        );

        PlaneSectionValidator.validate(context(planes));
    }
}
