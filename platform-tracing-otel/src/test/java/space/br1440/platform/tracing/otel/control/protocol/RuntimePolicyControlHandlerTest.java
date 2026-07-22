package space.br1440.platform.tracing.otel.control.protocol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocol;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolDecodeResult;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolKeys;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolOperation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RuntimePolicyControlHandler}.
 *
 * <p>Covers the three terminal states of the pipeline:
 * <ul>
 *   <li>{@link RuntimePolicyControlHandleResult.HandleStatus#DECODE_REJECTED}</li>
 *   <li>{@link RuntimePolicyControlHandleResult.HandleStatus#DOMAIN_REJECTED}</li>
 *   <li>{@link RuntimePolicyControlHandleResult.HandleStatus#SUCCESS}</li>
 * </ul>
 *
 * <p>Uses a simple recording {@link RuntimePolicyApplier} stub instead of
 * Mockito to avoid a test-scope dependency on a mocking framework.
 */
class RuntimePolicyControlHandlerTest {

    /** Stub applier that records every call for assertion. */
    private static final class RecordingApplier implements RuntimePolicyApplier {
        final List<Map<String, Object>> appliedPayloads = new ArrayList<>();

        @Override
        public void apply(TracingControlProtocolOperation operation,
                          Map<String, Object> normalizedPayload,
                          String source) {
            appliedPayloads.add(new LinkedHashMap<>(normalizedPayload));
        }
    }

    private RecordingApplier applier;
    private RuntimePolicyControlHandler handler;

    @BeforeEach
    void setUp() {
        applier = new RecordingApplier();
        handler = new RuntimePolicyControlHandler(
                applier,
                RuntimeControlMutationPolicy.startupConfigured(true));
    }

    // =========================================================================
    // DECODE_REJECTED
    // =========================================================================

    @Test
    void returnsDecodeRejectedWhenPayloadIsMalformed() {
        // Missing required 'operation' key → decode failure.
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        // 'operation' intentionally absent

        TracingControlProtocolDecodeResult decoded =
                TracingControlProtocol.current().decode(raw);

        RuntimePolicyControlHandleResult result = handler.handle(decoded);

        assertThat(result.isRejected()).isTrue();
        assertThat(result.status())
                .isEqualTo(RuntimePolicyControlHandleResult.HandleStatus.DECODE_REJECTED);
        assertThat(result.operation()).isEmpty();
        assertThat(result.violations()).isNotEmpty();
        assertThat(applier.appliedPayloads).isEmpty();
    }

    // =========================================================================
    // DOMAIN_REJECTED
    // =========================================================================

    @Test
    void returnsDomainRejectedWhenSamplingRatioOutOfBounds() {
        Map<String, Object> raw = validApplyRuntimePolicyPayload();
        raw.put(TracingControlProtocolKeys.SAMPLING_RATIO, 1.5d); // out of [0,1]

        TracingControlProtocolDecodeResult decoded =
                TracingControlProtocol.current().decode(raw);
        assertThat(decoded.valid()).isTrue(); // structural decode passes

        RuntimePolicyControlHandleResult result = handler.handle(decoded);

        assertThat(result.status())
                .isEqualTo(RuntimePolicyControlHandleResult.HandleStatus.DOMAIN_REJECTED);
        assertThat(result.operation())
                .contains(TracingControlProtocolOperation.APPLY_RUNTIME_POLICY);
        assertThat(result.violations()).isNotEmpty();
        assertThat(applier.appliedPayloads).isEmpty();
    }

    @Test
    void returnsDomainRejectedWhenValidationModeConflictsWithStrictFlag() {
        Map<String, Object> raw = validApplyRuntimePolicyPayload();
        raw.put(TracingControlProtocolKeys.VALIDATION_MODE,   "LOG_ONLY");
        raw.put(TracingControlProtocolKeys.VALIDATION_STRICT, true); // conflict

        TracingControlProtocolDecodeResult decoded =
                TracingControlProtocol.current().decode(raw);
        assertThat(decoded.valid()).isTrue(); // structural decode passes

        RuntimePolicyControlHandleResult result = handler.handle(decoded);

        assertThat(result.status())
                .isEqualTo(RuntimePolicyControlHandleResult.HandleStatus.DOMAIN_REJECTED);
        assertThat(result.violations())
                .anyMatch(v -> v.contains("conflicts with"));
        assertThat(applier.appliedPayloads).isEmpty();
    }

    @Test
    void returnsDomainRejectedWhenValidationModeUnrecognised() {
        Map<String, Object> raw = validApplyRuntimePolicyPayload();
        raw.put(TracingControlProtocolKeys.VALIDATION_MODE, "TURBO"); // unknown

        TracingControlProtocolDecodeResult decoded =
                TracingControlProtocol.current().decode(raw);
        assertThat(decoded.valid()).isTrue();

        RuntimePolicyControlHandleResult result = handler.handle(decoded);

        assertThat(result.status())
                .isEqualTo(RuntimePolicyControlHandleResult.HandleStatus.DOMAIN_REJECTED);
        assertThat(result.violations())
                .anyMatch(v -> v.contains("not a recognised mode"));
        assertThat(applier.appliedPayloads).isEmpty();
    }

    @Test
    void collectsBothSamplingAndValidationViolations() {
        Map<String, Object> raw = validApplyRuntimePolicyPayload();
        raw.put(TracingControlProtocolKeys.SAMPLING_RATIO, 2.0d);      // sampling violation
        raw.put(TracingControlProtocolKeys.VALIDATION_MODE, "LOG_ONLY");
        raw.put(TracingControlProtocolKeys.VALIDATION_STRICT, true);    // mode conflict

        TracingControlProtocolDecodeResult decoded =
                TracingControlProtocol.current().decode(raw);

        RuntimePolicyControlHandleResult result = handler.handle(decoded);

        assertThat(result.status())
                .isEqualTo(RuntimePolicyControlHandleResult.HandleStatus.DOMAIN_REJECTED);
        assertThat(result.violations()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(applier.appliedPayloads).isEmpty();
    }

    // =========================================================================
    // SUCCESS – mutating operation
    // =========================================================================

    @Test
    void successfullyAppliesValidSamplingRatioPayload() {
        Map<String, Object> raw = validApplyRuntimePolicyPayload();
        raw.put(TracingControlProtocolKeys.SAMPLING_RATIO, 0.5d);

        TracingControlProtocolDecodeResult decoded =
                TracingControlProtocol.current().decode(raw);

        RuntimePolicyControlHandleResult result = handler.handle(decoded);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.operation())
                .contains(TracingControlProtocolOperation.APPLY_RUNTIME_POLICY);
        assertThat(result.violations()).isEmpty();
        assertThat(applier.appliedPayloads).hasSize(1);
        assertThat(applier.appliedPayloads.getFirst())
                .containsEntry(TracingControlProtocolKeys.SAMPLING_RATIO, 0.5d);
    }

    @Test
    void successfullyAppliesValidationModeLogOnly() {
        Map<String, Object> raw = validApplyRuntimePolicyPayload();
        raw.put(TracingControlProtocolKeys.VALIDATION_MODE, "LOG_ONLY");

        TracingControlProtocolDecodeResult decoded =
                TracingControlProtocol.current().decode(raw);

        RuntimePolicyControlHandleResult result = handler.handle(decoded);

        assertThat(result.isSuccess()).isTrue();
        assertThat(applier.appliedPayloads).hasSize(1);
        assertThat(applier.appliedPayloads.getFirst())
                .containsEntry(TracingControlProtocolKeys.VALIDATION_MODE, "LOG_ONLY");
    }

    @Test
    void successfullyAppliesValidationModeStrict() {
        Map<String, Object> raw = validApplyRuntimePolicyPayload();
        raw.put(TracingControlProtocolKeys.VALIDATION_MODE,   "STRICT");
        raw.put(TracingControlProtocolKeys.VALIDATION_STRICT, true);

        TracingControlProtocolDecodeResult decoded =
                TracingControlProtocol.current().decode(raw);

        RuntimePolicyControlHandleResult result = handler.handle(decoded);

        assertThat(result.isSuccess()).isTrue();
        assertThat(applier.appliedPayloads).hasSize(1);
    }

    @Test
    void deniesApplyByDefaultWithoutCallingApplier() {
        RuntimePolicyControlHandler disabledHandler = new RuntimePolicyControlHandler(applier);
        Map<String, Object> raw = validApplyRuntimePolicyPayload();
        raw.put(TracingControlProtocolKeys.SAMPLING_RATIO, 0.5d);

        RuntimePolicyControlHandleResult result = disabledHandler.handle(
                TracingControlProtocol.current().decode(raw));

        assertThat(result.status())
                .isEqualTo(RuntimePolicyControlHandleResult.HandleStatus.MUTATION_REJECTED);
        assertThat(result.violations()).singleElement()
                .asString().contains("runtime mutation is disabled");
        assertThat(applier.appliedPayloads).isEmpty();
    }

    @Test
    void validateOperationDoesNotCallApplierWhenMutationIsDisabled() {
        RuntimePolicyControlHandler disabledHandler = new RuntimePolicyControlHandler(applier);
        Map<String, Object> raw = validDryRunPayload();
        raw.put(TracingControlProtocolKeys.SAMPLING_RATIO, 0.5d);

        RuntimePolicyControlHandleResult result = disabledHandler.handle(
                TracingControlProtocol.current().decode(raw));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.operation())
                .contains(TracingControlProtocolOperation.VALIDATE_RUNTIME_POLICY);
        assertThat(applier.appliedPayloads).isEmpty();
    }

    @Test
    void disabledMutationPolicyDoesNotMaskDecodeRejection() {
        RuntimePolicyControlHandler disabledHandler = new RuntimePolicyControlHandler(applier);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);

        RuntimePolicyControlHandleResult result = disabledHandler.handle(
                TracingControlProtocol.current().decode(raw));

        assertThat(result.status())
                .isEqualTo(RuntimePolicyControlHandleResult.HandleStatus.DECODE_REJECTED);
        assertThat(applier.appliedPayloads).isEmpty();
    }

    @Test
    void disabledMutationPolicyDoesNotMaskDomainRejection() {
        RuntimePolicyControlHandler disabledHandler = new RuntimePolicyControlHandler(applier);
        Map<String, Object> raw = validApplyRuntimePolicyPayload();
        raw.put(TracingControlProtocolKeys.SAMPLING_RATIO, 1.5d);

        RuntimePolicyControlHandleResult result = disabledHandler.handle(
                TracingControlProtocol.current().decode(raw));

        assertThat(result.status())
                .isEqualTo(RuntimePolicyControlHandleResult.HandleStatus.DOMAIN_REJECTED);
        assertThat(applier.appliedPayloads).isEmpty();
    }

    // =========================================================================
    // SUCCESS – read-only operation (no apply)
    // =========================================================================

    @Test
    void readAppliedStateIsShortCircuitedWithoutCallingApplier() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        raw.put(TracingControlProtocolKeys.OPERATION,
                TracingControlProtocolOperation.READ_APPLIED_STATE.wireValue());

        TracingControlProtocolDecodeResult decoded =
                TracingControlProtocol.current().decode(raw);
        assertThat(decoded.valid()).isTrue();

        RuntimePolicyControlHandleResult result = handler.handle(decoded);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.operation())
                .contains(TracingControlProtocolOperation.READ_APPLIED_STATE);
        assertThat(applier.appliedPayloads).isEmpty(); // applier must NOT be called
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Map<String, Object> validApplyRuntimePolicyPayload() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        map.put(TracingControlProtocolKeys.OPERATION,
                TracingControlProtocolOperation.APPLY_RUNTIME_POLICY.wireValue());
        return map;
    }

    private static Map<String, Object> validDryRunPayload() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        map.put(TracingControlProtocolKeys.OPERATION,
                TracingControlProtocolOperation.VALIDATE_RUNTIME_POLICY.wireValue());
        return map;
    }
}
