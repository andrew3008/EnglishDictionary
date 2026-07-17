package space.br1440.platform.tracing.api.control.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that {@link TracingControlProtocolDecodeResult} carries the right
 * {@link TracingControlProtocolViolationCode} for common structural error scenarios.
 *
 * <p>Migrated from {@code result/TracingControlProtocolViolationTest} which relied on
 * the deleted {@code validator().validateRuntimePolicy()} API. Rewritten to use
 * {@code TracingControlProtocol.current().decode(Map)} which is the canonical entry-point
 * after slice-3 refactor.
 */
@DisplayName("TracingControlProtocolViolation: violation codes via decode()")
class TracingControlProtocolViolationTest {

    @Test
    @DisplayName("unsupported version 2 → first violation is UNSUPPORTED_VERSION")
    void unsupportedVersionCode() {
        Map<String, Object> payload = new HashMap<>();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 2);
        payload.put(TracingControlProtocolKeys.OPERATION,
                TracingControlProtocolKeys.OPERATION_APPLY_RUNTIME_POLICY);

        TracingControlProtocolDecodeResult result =
                TracingControlProtocol.current().decode(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).isNotEmpty();
        assertThat(result.violations().get(0).code())
                .isEqualTo(TracingControlProtocolViolationCode.UNSUPPORTED_VERSION);
    }

    @Test
    @DisplayName("malformed contractVersion 'abc' → first violation is INVALID_VALUE")
    void invalidValueCode() {
        Map<String, Object> payload = new HashMap<>();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, "abc");
        payload.put(TracingControlProtocolKeys.OPERATION,
                TracingControlProtocolKeys.OPERATION_APPLY_RUNTIME_POLICY);

        TracingControlProtocolDecodeResult result =
                TracingControlProtocol.current().decode(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).isNotEmpty();
        assertThat(result.violations().get(0).code())
                .isEqualTo(TracingControlProtocolViolationCode.INVALID_VALUE);
    }

    @Test
    @DisplayName("unknown key in otherwise valid payload → first violation is UNKNOWN_KEY")
    void unknownKeyCode() {
        Map<String, Object> payload = new HashMap<>();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        payload.put(TracingControlProtocolKeys.OPERATION,
                TracingControlProtocolKeys.OPERATION_APPLY_RUNTIME_POLICY);
        payload.put("unknown", true);

        TracingControlProtocolDecodeResult result =
                TracingControlProtocol.current().decode(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).isNotEmpty();
        assertThat(result.violations().get(0).code())
                .isEqualTo(TracingControlProtocolViolationCode.UNKNOWN_KEY);
    }

    @Test
    @DisplayName("null payload → valid=false with at least one violation")
    void nullPayload_isInvalid() {
        TracingControlProtocolDecodeResult result =
                TracingControlProtocol.current().decode(null);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).isNotEmpty();
    }

    @Test
    @DisplayName("empty payload → valid=false with MISSING_REQUIRED_KEY violation")
    void emptyPayload_isInvalid() {
        TracingControlProtocolDecodeResult result =
                TracingControlProtocol.current().decode(Map.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).anyMatch(v ->
                v.code() == TracingControlProtocolViolationCode.MISSING_REQUIRED_KEY);
    }
}
