package space.br1440.platform.tracing.jmx.contract.tool.validator;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.jmx.contract.tool.ContractValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilitiesSectionValidatorTest {

    private static ContractValidationContext context(List<String> required) {
        Map<String, Object> root = Map.of("capabilities", Map.of("required", required));
        return new ContractValidationContext(root, Map.of(), Map.of(), Map.of());
    }

    @Test
    void rejectsDuplicateCapability() {
        assertThatThrownBy(() -> CapabilitiesSectionValidator.validate(context(List.of("READ", "READ"))))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_CAPABILITIES_INVALID"));
    }

    @Test
    void rejectsLowercaseCapabilityIdentifier() {
        assertThatThrownBy(() -> CapabilitiesSectionValidator.validate(context(List.of("read"))))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_CAPABILITIES_INVALID"));
    }

    @Test
    void acceptsUniqueUpperSnakeCapabilities() {
        CapabilitiesSectionValidator.validate(context(List.of("READ", "WRITE_SNAPSHOT")));
    }
}
