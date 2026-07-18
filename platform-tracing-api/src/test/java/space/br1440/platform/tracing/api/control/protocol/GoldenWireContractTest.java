package space.br1440.platform.tracing.api.control.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Golden wire contract examples")
class GoldenWireContractTest {

    @Test
    void applyRuntimePolicyExampleDecodesSuccessfully() {
        Map<String, Object> payload = base(TracingControlProtocolOperation.APPLY_RUNTIME_POLICY);
        payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 0.5d);
        payload.put(TracingControlProtocolKeys.VALIDATION_MODE, "STRICT");
        payload.put(TracingControlProtocolKeys.DIAGNOSTICS_REQUEST_ID, "golden-apply");

        TracingControlProtocolDecodeResult result = TracingControlProtocol.current().decode(payload);

        assertThat(result.valid()).isTrue();
        assertThat(result.operation()).contains(TracingControlProtocolOperation.APPLY_RUNTIME_POLICY);
        assertThat(result.normalizedPayload()).containsEntry(TracingControlProtocolKeys.SAMPLING_RATIO, 0.5d);
    }

    @Test
    void readAppliedStateExampleRejectsPolicyFields() {
        Map<String, Object> payload = base(TracingControlProtocolOperation.READ_APPLIED_STATE);
        payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 0.5d);

        TracingControlProtocolDecodeResult result = TracingControlProtocol.current().decode(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations())
                .anySatisfy(violation -> {
                    assertThat(violation.key()).isEqualTo(TracingControlProtocolKeys.SAMPLING_RATIO);
                    assertThat(violation.code()).isEqualTo(TracingControlProtocolViolationCode.UNKNOWN_KEY);
                });
    }

    @Test
    void missingContractVersionExampleUsesMissingRequiredKey() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TracingControlProtocolKeys.OPERATION,
                TracingControlProtocolOperation.VALIDATE_RUNTIME_POLICY.wireValue());

        TracingControlProtocolDecodeResult result = TracingControlProtocol.current().decode(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations())
                .anySatisfy(violation -> {
                    assertThat(violation.key()).isEqualTo(TracingControlProtocolKeys.CONTRACT_VERSION);
                    assertThat(violation.code()).isEqualTo(TracingControlProtocolViolationCode.MISSING_REQUIRED_KEY);
                });
    }

    private static Map<String, Object> base(TracingControlProtocolOperation operation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        payload.put(TracingControlProtocolKeys.OPERATION, operation.wireValue());
        return payload;
    }
}
