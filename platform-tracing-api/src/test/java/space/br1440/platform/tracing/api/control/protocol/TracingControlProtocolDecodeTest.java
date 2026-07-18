package space.br1440.platform.tracing.api.control.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TracingControlProtocol.decode")
class TracingControlProtocolDecodeTest {

    @Test
    void decodesApplyRuntimePolicyPayload() {
        Map<String, Object> payload = base(TracingControlProtocolOperation.APPLY_RUNTIME_POLICY);
        payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 1.1d);
        payload.put(TracingControlProtocolKeys.VALIDATION_MODE, "custom-mode");

        TracingControlProtocolDecodeResult result = TracingControlProtocol.current().decode(payload);

        assertThat(result.valid()).isTrue();
        assertThat(result.operation()).contains(TracingControlProtocolOperation.APPLY_RUNTIME_POLICY);
        assertThat(result.normalizedPayload())
                .containsEntry(TracingControlProtocolKeys.CONTRACT_VERSION, 1)
                .containsEntry(TracingControlProtocolKeys.OPERATION,
                        TracingControlProtocolOperation.APPLY_RUNTIME_POLICY.wireValue())
                .containsEntry(TracingControlProtocolKeys.SAMPLING_RATIO, 1.1d)
                .containsEntry(TracingControlProtocolKeys.VALIDATION_MODE, "custom-mode");
    }

    @Test
    void decodesValidateRuntimePolicyPayload() {
        Map<String, Object> payload = base(TracingControlProtocolOperation.VALIDATE_RUNTIME_POLICY);
        payload.put(TracingControlProtocolKeys.SAMPLING_FORCE_HEADER_VALUES, new String[]{"on", "1"});

        TracingControlProtocolDecodeResult result = TracingControlProtocol.current().decode(payload);

        assertThat(result.valid()).isTrue();
        assertThat(result.operation()).contains(TracingControlProtocolOperation.VALIDATE_RUNTIME_POLICY);
    }

    @Test
    void decodesReadAppliedStateRequest() {
        Map<String, Object> payload = base(TracingControlProtocolOperation.READ_APPLIED_STATE);
        payload.put(TracingControlProtocolKeys.DIAGNOSTICS_REQUEST_ID, "read-1");

        TracingControlProtocolDecodeResult result = TracingControlProtocol.current().decode(payload);

        assertThat(result.valid()).isTrue();
        assertThat(result.operation()).contains(TracingControlProtocolOperation.READ_APPLIED_STATE);
        assertThat(result.normalizedPayload())
                .containsEntry(TracingControlProtocolKeys.DIAGNOSTICS_REQUEST_ID, "read-1");
    }

    @Test
    void readAppliedStateRejectsRuntimePolicyKeyAsUnknownForOperation() {
        Map<String, Object> payload = base(TracingControlProtocolOperation.READ_APPLIED_STATE);
        payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 0.5d);

        TracingControlProtocolDecodeResult result = TracingControlProtocol.current().decode(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.operation()).contains(TracingControlProtocolOperation.READ_APPLIED_STATE);
        assertThat(result.normalizedPayload()).isEmpty();
        assertThat(result.violations())
                .anySatisfy(v -> {
                    assertThat(v.key()).isEqualTo(TracingControlProtocolKeys.SAMPLING_RATIO);
                    assertThat(v.code()).isEqualTo(TracingControlProtocolViolationCode.UNKNOWN_KEY);
                });
    }

    @Test
    void unknownOperationUsesOperationNotAllowedWithoutNewCode() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        payload.put(TracingControlProtocolKeys.OPERATION, "UNKNOWN_CONTROL_OPERATION");

        TracingControlProtocolDecodeResult result = TracingControlProtocol.current().decode(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.operation()).isEmpty();
        assertThat(result.violations())
                .singleElement()
                .satisfies(v -> {
                    assertThat(v.key()).isEqualTo(TracingControlProtocolKeys.OPERATION);
                    assertThat(v.code()).isEqualTo(TracingControlProtocolViolationCode.OPERATION_NOT_ALLOWED);
                });
    }

    @Test
    void nonStringOperationIsTypeMismatch() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        payload.put(TracingControlProtocolKeys.OPERATION, 42);

        TracingControlProtocolDecodeResult result = TracingControlProtocol.current().decode(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.operation()).isEmpty();
        assertThat(result.violations())
                .singleElement()
                .extracting(TracingControlProtocolViolation::code)
                .isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
    }

    @Test
    void missingOperationKeepsOperationEmpty() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);

        TracingControlProtocolDecodeResult result = TracingControlProtocol.current().decode(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.operation()).isEmpty();
        assertThat(result.violations())
                .singleElement()
                .extracting(TracingControlProtocolViolation::code)
                .isEqualTo(TracingControlProtocolViolationCode.MISSING_REQUIRED_KEY);
    }

    @Test
    void invalidResultNeverExposesPartialPayload() {
        Map<String, Object> payload = base(TracingControlProtocolOperation.APPLY_RUNTIME_POLICY);
        payload.put(TracingControlProtocolKeys.SOURCE, new Object());

        TracingControlProtocolDecodeResult result = TracingControlProtocol.current().decode(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.operation()).contains(TracingControlProtocolOperation.APPLY_RUNTIME_POLICY);
        assertThat(result.normalizedPayload()).isEmpty();
    }

    @Test
    void routeRatiosNormalizeShapeWithoutDomainBounds() {
        Map<String, Object> ratios = new LinkedHashMap<>();
        ratios.put("/api", 2);
        ratios.put("/admin", -0.5d);
        Map<String, Object> payload = base(TracingControlProtocolOperation.APPLY_RUNTIME_POLICY);
        payload.put(TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS, ratios);

        TracingControlProtocolDecodeResult result = TracingControlProtocol.current().decode(payload);

        assertThat(result.valid()).isTrue();
        assertThat(result.normalizedPayload().get(TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS))
                .isEqualTo(Map.of("/api", 2.0d, "/admin", -0.5d));
    }

    @Test
    void decodeResultInvariantsAreEnforced() {
        assertThatThrownBy(() -> new TracingControlProtocolDecodeResult(true,
                java.util.Optional.empty(), Map.of(), java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> TracingControlProtocolDecodeResult.failure(
                null, java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, Object> base(TracingControlProtocolOperation operation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        payload.put(TracingControlProtocolKeys.OPERATION, operation.wireValue());
        return payload;
    }
}
