package space.br1440.platform.tracing.jmx.contract.tool;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractValuesTest {

    @Test
    void assertKeysRejectsUnknownField() {
        assertThatThrownBy(() -> ContractValues.assertKeys(
                Map.of("x", 1), "p", Set.of("y"), Set.of()))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_UNKNOWN_FIELD"));
    }

    @Test
    void assertKeysRejectsMissingRequiredField() {
        assertThatThrownBy(() -> ContractValues.assertKeys(
                Map.of(), "p", Set.of("y"), Set.of("y")))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_REQUIRED_FIELD_MISSING"));
    }

    @Test
    void stringRejectsNonStringValue() {
        assertThatThrownBy(() -> ContractValues.string(42, "p"))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_FIELD_TYPE_MISMATCH"));
    }

    @Test
    void requireValueRejectsUnsupportedValue() {
        assertThatThrownBy(() -> ContractValues.requireValue(
                Map.of("k", "CRLF"), "k", "LF", "p"))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_VALUE_UNSUPPORTED"));
    }

    @Test
    void stringsRoundTripsList() {
        assertThat(ContractValues.strings(List.of("a", "b"), "p")).containsExactly("a", "b");
    }

    @Test
    void integerRejectsNonIntegralNumber() {
        assertThatThrownBy(() -> ContractValues.integer(1.5, "p"))
                .isInstanceOf(ContractValidationException.class);
    }

    @Test
    void boolRejectsNonBoolean() {
        assertThatThrownBy(() -> ContractValues.bool("true", "p"))
                .isInstanceOf(ContractValidationException.class);
    }
}
