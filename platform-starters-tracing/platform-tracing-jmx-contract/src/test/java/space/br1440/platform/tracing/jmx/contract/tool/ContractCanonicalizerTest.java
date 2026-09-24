package space.br1440.platform.tracing.jmx.contract.tool;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractCanonicalizerTest {

    @Test
    void canonicalRejectsNonFiniteNumber() {
        assertThatThrownBy(() -> ContractCanonicalizer.canonical(Map.of("k", Double.NaN)))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_NUMBER_INVALID"));
    }

    @Test
    void canonicalOrdersMapKeysByCodePoint() {
        String first = ContractCanonicalizer.canonical(Map.of("b", 1, "a", 2));
        String second = ContractCanonicalizer.canonical(Map.of("a", 2, "b", 1));
        assertThat(first).isEqualTo(second);
    }

    @Test
    void sha256IsDeterministic() {
        assertThat(ContractCanonicalizer.sha256("value"))
                .isEqualTo("cd42404d52ad55ccfa9aca4adc828aa5800ad9d385a0671fbcbf724118320619");
    }
}
