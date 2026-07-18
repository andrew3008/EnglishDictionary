package space.br1440.platform.tracing.api.control.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TracingControlProtocol")
class TracingControlProtocolTest {

    private static final TracingControlProtocolVersion VERSION_1 = new TracingControlProtocolVersion(1);

    @Test
    @DisplayName("current() returns same instance identity on repeated calls")
    void currentReturnsSameInstance() {
        TracingControlProtocol first = TracingControlProtocol.current();
        TracingControlProtocol second = TracingControlProtocol.current();

        assertThat(first).isSameAs(second);
    }

    @Test
    @DisplayName("current().version() is version 1")
    void currentVersionIsOne() {
        assertThat(TracingControlProtocol.current().version()).isEqualTo(VERSION_1);
    }

    @Test
    @DisplayName("current().decode(...) accepts a minimal valid payload")
    void currentDecodeAcceptsMinimalValidPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        payload.put(TracingControlProtocolKeys.OPERATION,
                TracingControlProtocolOperation.VALIDATE_RUNTIME_POLICY.wireValue());

        TracingControlProtocolDecodeResult result = TracingControlProtocol.current().decode(payload);

        assertThat(result.valid()).isTrue();
        assertThat(result.operation()).contains(TracingControlProtocolOperation.VALIDATE_RUNTIME_POLICY);
        assertThat(result.normalizedPayload())
                .containsEntry(TracingControlProtocolKeys.CONTRACT_VERSION, 1)
                .containsEntry(TracingControlProtocolKeys.OPERATION,
                        TracingControlProtocolOperation.VALIDATE_RUNTIME_POLICY.wireValue());
    }
}
