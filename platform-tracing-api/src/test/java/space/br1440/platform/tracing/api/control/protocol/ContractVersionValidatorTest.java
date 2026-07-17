package space.br1440.platform.tracing.api.control.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * White-box seam tests for package-private {@code ContractVersionValidator}.
 *
 * <p>Lives in the same package as the production code so it can access the
 * package-private helper directly. Covers parse, support-check and normalization
 * paths independently of the full decoder pipeline.
 */
@DisplayName("ContractVersionValidator: contractVersion parse, support, and normalization")
class ContractVersionValidatorTest {

    // ─── helpers ────────────────────────────────────────────────────────────────

    private static List<TracingControlProtocolViolation> violations() {
        return new ArrayList<>();
    }

    private static Map<String, Object> normalized() {
        return new LinkedHashMap<>();
    }

    private static void validate(Object value,
                                 List<TracingControlProtocolViolation> v,
                                 Map<String, Object> n) {
        ContractVersionValidator.validate(TracingControlProtocolKeys.CONTRACT_VERSION, value, v, n);
    }

    // ─── INVALID_VALUE paths ─────────────────────────────────────────────────────

    @Test
    @DisplayName("null value → INVALID_VALUE, reason 'invalid contractVersion', expectedType 'Integer'")
    void nullValue_returnsInvalidValue() {
        List<TracingControlProtocolViolation> v = violations();
        Map<String, Object> n = normalized();
        validate(null, v, n);

        assertThat(v).hasSize(1);
        assertThat(v.get(0).key()).isEqualTo(TracingControlProtocolKeys.CONTRACT_VERSION);
        assertThat(v.get(0).code()).isEqualTo(TracingControlProtocolViolationCode.INVALID_VALUE);
        assertThat(v.get(0).reason()).isEqualTo("invalid contractVersion");
        assertThat(v.get(0).expectedType()).isEqualTo("Integer");
        assertThat(v.get(0).actualType()).isEqualTo("null");
        assertThat(n).doesNotContainKey(TracingControlProtocolKeys.CONTRACT_VERSION);
    }

    @Test
    @DisplayName("malformed string 'abc' → INVALID_VALUE, reason 'invalid contractVersion'")
    void malformedString_returnsInvalidValue() {
        List<TracingControlProtocolViolation> v = violations();
        Map<String, Object> n = normalized();
        validate("abc", v, n);

        assertThat(v).hasSize(1);
        assertThat(v.get(0).code()).isEqualTo(TracingControlProtocolViolationCode.INVALID_VALUE);
        assertThat(v.get(0).reason()).isEqualTo("invalid contractVersion");
        assertThat(n).doesNotContainKey(TracingControlProtocolKeys.CONTRACT_VERSION);
    }

    @Test
    @DisplayName("Long.MAX_VALUE → INVALID_VALUE (out of int range, parse returns empty)")
    void longMaxValue_returnsInvalidValue() {
        List<TracingControlProtocolViolation> v = violations();
        Map<String, Object> n = normalized();
        validate(Long.MAX_VALUE, v, n);

        assertThat(v).hasSize(1);
        assertThat(v.get(0).code()).isEqualTo(TracingControlProtocolViolationCode.INVALID_VALUE);
        assertThat(v.get(0).reason()).isEqualTo("invalid contractVersion");
        assertThat(n).doesNotContainKey(TracingControlProtocolKeys.CONTRACT_VERSION);
    }

    // ─── UNSUPPORTED_VERSION path ─────────────────────────────────────────────────

    @Test
    @DisplayName("version 2 (parseable, unsupported) → UNSUPPORTED_VERSION, expectedType '1'")
    void unsupportedInteger_returnsUnsupportedVersion() {
        List<TracingControlProtocolViolation> v = violations();
        Map<String, Object> n = normalized();
        validate(2, v, n);

        assertThat(v).hasSize(1);
        assertThat(v.get(0).code()).isEqualTo(TracingControlProtocolViolationCode.UNSUPPORTED_VERSION);
        assertThat(v.get(0).reason()).isEqualTo("unsupported contractVersion");
        assertThat(v.get(0).expectedType()).isEqualTo("1");
        assertThat(n).doesNotContainKey(TracingControlProtocolKeys.CONTRACT_VERSION);
    }

    // ─── Valid paths — normalization ──────────────────────────────────────────────

    @Test
    @DisplayName("Long 1L (in int range) → valid, normalized to Integer 1")
    void longOne_valid_normalizesToIntegerMajor() {
        List<TracingControlProtocolViolation> v = violations();
        Map<String, Object> n = normalized();
        validate(1L, v, n);

        assertThat(v).isEmpty();
        assertThat(n).containsKey(TracingControlProtocolKeys.CONTRACT_VERSION);
        Object normalizedVersion = n.get(TracingControlProtocolKeys.CONTRACT_VERSION);
        assertThat(normalizedVersion).isInstanceOf(Integer.class);
        assertThat(normalizedVersion).isEqualTo(1);
    }

    @Test
    @DisplayName("String '1' → valid, normalized to Integer 1")
    void stringOne_valid_normalizesToIntegerMajor() {
        List<TracingControlProtocolViolation> v = violations();
        Map<String, Object> n = normalized();
        validate("1", v, n);

        assertThat(v).isEmpty();
        Object normalizedVersion = n.get(TracingControlProtocolKeys.CONTRACT_VERSION);
        assertThat(normalizedVersion).isInstanceOf(Integer.class);
        assertThat(normalizedVersion).isEqualTo(1);
    }

    @Test
    @DisplayName("String ' 1 ' (with whitespace) → valid, normalized to Integer 1")
    void stringOneWithWhitespace_valid_normalizesToIntegerMajor() {
        List<TracingControlProtocolViolation> v = violations();
        Map<String, Object> n = normalized();
        validate(" 1 ", v, n);

        assertThat(v).isEmpty();
        Object normalizedVersion = n.get(TracingControlProtocolKeys.CONTRACT_VERSION);
        assertThat(normalizedVersion).isInstanceOf(Integer.class);
        assertThat(normalizedVersion).isEqualTo(1);
    }

    @Test
    @DisplayName("Integer 1 → valid, normalized to Integer 1")
    void integerOne_valid_normalizesToIntegerMajor() {
        List<TracingControlProtocolViolation> v = violations();
        Map<String, Object> n = normalized();
        validate(1, v, n);

        assertThat(v).isEmpty();
        Object normalizedVersion = n.get(TracingControlProtocolKeys.CONTRACT_VERSION);
        assertThat(normalizedVersion).isInstanceOf(Integer.class);
        assertThat(normalizedVersion).isEqualTo(1);
    }
}
