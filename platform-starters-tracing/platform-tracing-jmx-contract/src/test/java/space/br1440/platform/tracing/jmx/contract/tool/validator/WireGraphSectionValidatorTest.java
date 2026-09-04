package space.br1440.platform.tracing.jmx.contract.tool.validator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.jmx.contract.tool.ContractValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WireGraphSectionValidatorTest {

    private static Map<String, Object> validWireGraph() {
        return Map.of(
                "maxDepth", 8,
                "maxElements", 64,
                "maxLogicalBytes", 65536,
                "maxStringUtf8Bytes", 4096,
                "maxRouteEntries", 64,
                "maxRoutePrefixUtf8Bytes", 256,
                "maxCapabilityEntries", 16,
                "allowedNeutralTypes", List.of("string", "boolean"),
                "allowedArrayComponents", List.of("string")
        );
    }

    private static ContractValidationContext context(Map<String, Object> wireGraph) {
        Map<String, Object> root = Map.of("wireGraph", wireGraph);
        return new ContractValidationContext(root, Map.of(), Map.of(), Map.of());
    }

    @Test
    void rejectsNonPositiveBound() {
        Map<String, Object> wireGraph = new HashMap<>(validWireGraph());
        wireGraph.put("maxDepth", 0);
        assertThatThrownBy(() -> WireGraphSectionValidator.validate(context(wireGraph)))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_WIRE_BOUND_INVALID"));
    }

    @Test
    void rejectsDuplicateAllowedNeutralType() {
        Map<String, Object> wireGraph = new HashMap<>(validWireGraph());
        wireGraph.put("allowedNeutralTypes", List.of("string", "string"));
        assertThatThrownBy(() -> WireGraphSectionValidator.validate(context(wireGraph)))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_WIRE_ALLOWLIST_INVALID"));
    }

    @Test
    void acceptsValidWireGraph() {
        WireGraphSectionValidator.validate(context(validWireGraph()));
    }
}
