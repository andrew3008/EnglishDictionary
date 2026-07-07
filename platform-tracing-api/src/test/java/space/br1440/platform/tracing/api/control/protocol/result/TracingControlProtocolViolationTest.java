package space.br1440.platform.tracing.api.control.protocol.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocol;
import space.br1440.platform.tracing.api.control.protocol.schema.TracingControlProtocolKeys;
import space.br1440.platform.tracing.api.control.protocol.validation.TracingControlProtocolViolationCode;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TracingControlProtocolViolation")
class TracingControlProtocolViolationTest {

    @Test
    @DisplayName("unsupported version maps to UNSUPPORTED_VERSION")
    void unsupportedVersionCode() {
        Map<String, Object> payload = new HashMap<>();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 2);
        payload.put(TracingControlProtocolKeys.OPERATION, TracingControlProtocolKeys.OPERATION_APPLY_RUNTIME_POLICY);

        var violation = TracingControlProtocol.current().validator()
                .validateRuntimePolicy(payload)
                .violations()
                .getFirst();

        assertThat(violation.code()).isEqualTo(TracingControlProtocolViolationCode.UNSUPPORTED_VERSION);
    }

    @Test
    @DisplayName("malformed contractVersion maps to INVALID_VALUE")
    void invalidValueCode() {
        Map<String, Object> payload = new HashMap<>();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, "abc");
        payload.put(TracingControlProtocolKeys.OPERATION, TracingControlProtocolKeys.OPERATION_APPLY_RUNTIME_POLICY);

        var violation = TracingControlProtocol.current().validator()
                .validateRuntimePolicy(payload)
                .violations()
                .getFirst();

        assertThat(violation.code()).isEqualTo(TracingControlProtocolViolationCode.INVALID_VALUE);
    }

    @Test
    @DisplayName("unknown key maps to UNKNOWN_KEY")
    void unknownKeyCode() {
        Map<String, Object> payload = new HashMap<>();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        payload.put(TracingControlProtocolKeys.OPERATION, TracingControlProtocolKeys.OPERATION_APPLY_RUNTIME_POLICY);
        payload.put("unknown", true);

        var violation = TracingControlProtocol.current().validator()
                .validateRuntimePolicy(payload)
                .violations()
                .getFirst();

        assertThat(violation.code()).isEqualTo(TracingControlProtocolViolationCode.UNKNOWN_KEY);
    }
}
