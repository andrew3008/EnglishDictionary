package space.br1440.platform.tracing.api.control.protocol.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.control.protocol.result.TracingControlProtocolViolation;
import space.br1440.platform.tracing.api.control.protocol.schema.TracingControlProtocolFieldCategory;
import space.br1440.platform.tracing.api.control.protocol.schema.TracingControlProtocolFieldDescriptor;
import space.br1440.platform.tracing.api.control.protocol.schema.TracingControlProtocolKeys;
import space.br1440.platform.tracing.api.control.protocol.schema.TracingControlProtocolTypes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct seam tests for {@link OperationSemanticsValidator}.
 *
 * <p>These tests verify category-policy rejection and operation field validation
 * independently of the full validator pipeline.
 *
 * <p>After Phase 3, allowlist ordering is deterministic (declaration order):
 * <ul>
 *   <li>Runtime mutation: {@code APPLY_RUNTIME_POLICY|VALIDATE_RUNTIME_POLICY}</li>
 *   <li>Read:             {@code READ_APPLIED_STATE|READ_SCHEMA}</li>
 * </ul>
 * The {@code expectedType} strings in {@code OPERATION_NOT_ALLOWED} violations are
 * therefore stable and can be asserted exactly.
 */
@DisplayName("OperationSemanticsValidator: category policy and operation validation")
class OperationSemanticsValidatorTest {

    // ─── helpers ────────────────────────────────────────────────────────────────

    private static List<TracingControlProtocolViolation> violations() {
        return new ArrayList<>();
    }

    private static Map<String, Object> normalized() {
        return new LinkedHashMap<>();
    }

    /** Builds a minimal descriptor for a given category (type and key are illustrative). */
    private static TracingControlProtocolFieldDescriptor descriptor(
            String key, TracingControlProtocolTypes type, TracingControlProtocolFieldCategory category) {
        return new TracingControlProtocolFieldDescriptor(key, type, category, Set.of());
    }

    // Deterministic allowlist constants — declaration order from Phase 3 production code.
    private static final List<String> RUNTIME_MUTATION_OPERATIONS = List.of(
            TracingControlProtocolKeys.OPERATION_APPLY_RUNTIME_POLICY,
            TracingControlProtocolKeys.OPERATION_VALIDATE_RUNTIME_POLICY
    );

    private static final List<String> READ_OPERATIONS = List.of(
            TracingControlProtocolKeys.OPERATION_READ_APPLIED_STATE,
            TracingControlProtocolKeys.OPERATION_READ_SCHEMA
    );

    // ─── Category policy — topology ──────────────────────────────────────────────

    @Test
    @DisplayName("STARTUP_TOPOLOGY field → OPERATION_NOT_ALLOWED, topology reason")
    void topologyFieldRejectedBeforeValueValidation() {
        List<TracingControlProtocolViolation> v = violations();
        TracingControlProtocolFieldDescriptor d = descriptor(
                TracingControlProtocolKeys.TOPOLOGY_EXPORTER_ENDPOINT,
                TracingControlProtocolTypes.STRING,
                TracingControlProtocolFieldCategory.STARTUP_TOPOLOGY);

        boolean rejected = OperationSemanticsValidator.validateCategoryPolicy(
                TracingControlProtocolKeys.TOPOLOGY_EXPORTER_ENDPOINT, d,
                true /* allowRuntimePolicyFields */, v);

        assertThat(rejected).isTrue();
        assertThat(v).hasSize(1);
        assertThat(v.get(0).code()).isEqualTo(TracingControlProtocolViolationCode.OPERATION_NOT_ALLOWED);
        assertThat(v.get(0).reason())
                .isEqualTo("startup topology field rejected for wire control path");
        assertThat(v.get(0).expectedType()).isEqualTo("runtime policy or envelope key");
        // actualType = descriptor.type().name() = "STRING"
        assertThat(v.get(0).actualType()).isEqualTo(TracingControlProtocolTypes.STRING.name());
        assertThat(v.get(0).key()).isEqualTo(TracingControlProtocolKeys.TOPOLOGY_EXPORTER_ENDPOINT);
    }

    // ─── Category policy — runtime policy on read path ────────────────────────────

    @Test
    @DisplayName("RUNTIME_POLICY field with allowRuntimePolicyFields=false → OPERATION_NOT_ALLOWED")
    void runtimePolicyFieldRejectedForReadRequest() {
        List<TracingControlProtocolViolation> v = violations();
        TracingControlProtocolFieldDescriptor d = descriptor(
                TracingControlProtocolKeys.SAMPLING_RATIO,
                TracingControlProtocolTypes.DOUBLE,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);

        boolean rejected = OperationSemanticsValidator.validateCategoryPolicy(
                TracingControlProtocolKeys.SAMPLING_RATIO, d,
                false /* allowRuntimePolicyFields = false for read request */, v);

        assertThat(rejected).isTrue();
        assertThat(v).hasSize(1);
        assertThat(v.get(0).code()).isEqualTo(TracingControlProtocolViolationCode.OPERATION_NOT_ALLOWED);
        assertThat(v.get(0).reason())
                .isEqualTo("runtime policy field not allowed in read request");
        assertThat(v.get(0).expectedType()).isEqualTo("envelope or diagnostic key");
        assertThat(v.get(0).actualType()).isEqualTo(TracingControlProtocolTypes.DOUBLE.name());
    }

    @Test
    @DisplayName("RUNTIME_POLICY field with allowRuntimePolicyFields=true → no violation")
    void runtimePolicyFieldAllowedForRuntimeEntry() {
        List<TracingControlProtocolViolation> v = violations();
        TracingControlProtocolFieldDescriptor d = descriptor(
                TracingControlProtocolKeys.SAMPLING_RATIO,
                TracingControlProtocolTypes.DOUBLE,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);

        boolean rejected = OperationSemanticsValidator.validateCategoryPolicy(
                TracingControlProtocolKeys.SAMPLING_RATIO, d,
                true /* allowRuntimePolicyFields */, v);

        assertThat(rejected).isFalse();
        assertThat(v).isEmpty();
    }

    @Test
    @DisplayName("ENVELOPE field never rejected by category policy")
    void envelopeFieldPassesCategoryPolicy() {
        List<TracingControlProtocolViolation> v = violations();
        TracingControlProtocolFieldDescriptor d = descriptor(
                TracingControlProtocolKeys.SOURCE,
                TracingControlProtocolTypes.STRING,
                TracingControlProtocolFieldCategory.ENVELOPE);

        boolean rejected = OperationSemanticsValidator.validateCategoryPolicy(
                TracingControlProtocolKeys.SOURCE, d, false, v);

        assertThat(rejected).isFalse();
        assertThat(v).isEmpty();
    }

    // ─── Operation field validation ────────────────────────────────────────────────

    @Test
    @DisplayName("operation value not String → TYPE_MISMATCH, reason 'operation must be String'")
    void operationNotString_returnsTypeMismatch() {
        List<TracingControlProtocolViolation> v = violations();
        Map<String, Object> n = normalized();

        OperationSemanticsValidator.validateOperation(
                TracingControlProtocolKeys.OPERATION, 42,
                RUNTIME_MUTATION_OPERATIONS, v, n);

        assertThat(v).hasSize(1);
        assertThat(v.get(0).code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
        assertThat(v.get(0).reason()).isEqualTo("operation must be String");
        assertThat(v.get(0).expectedType()).isEqualTo("String");
        assertThat(n).doesNotContainKey(TracingControlProtocolKeys.OPERATION);
    }

    @Test
    @DisplayName("READ_APPLIED_STATE sent to runtime allowlist → OPERATION_NOT_ALLOWED; " +
                 "expectedType = 'APPLY_RUNTIME_POLICY|VALIDATE_RUNTIME_POLICY' (exact)")
    void operationNotInRuntimeAllowlist_returnsOperationNotAllowed() {
        List<TracingControlProtocolViolation> v = violations();
        Map<String, Object> n = normalized();

        OperationSemanticsValidator.validateOperation(
                TracingControlProtocolKeys.OPERATION,
                TracingControlProtocolKeys.OPERATION_READ_APPLIED_STATE,
                RUNTIME_MUTATION_OPERATIONS, v, n);

        assertThat(v).hasSize(1);
        assertThat(v.get(0).code()).isEqualTo(TracingControlProtocolViolationCode.OPERATION_NOT_ALLOWED);
        assertThat(v.get(0).reason())
                .isEqualTo("unsupported operation for this validation entry point");
        // Deterministic after Phase 3: declaration order = APPLY|VALIDATE
        assertThat(v.get(0).expectedType()).isEqualTo(
                TracingControlProtocolKeys.OPERATION_APPLY_RUNTIME_POLICY
                + "|"
                + TracingControlProtocolKeys.OPERATION_VALIDATE_RUNTIME_POLICY);
        assertThat(v.get(0).actualType())
                .isEqualTo(TracingControlProtocolKeys.OPERATION_READ_APPLIED_STATE);
        assertThat(n).doesNotContainKey(TracingControlProtocolKeys.OPERATION);
    }

    @Test
    @DisplayName("valid APPLY_RUNTIME_POLICY in runtime allowlist → normalized to String, no violations")
    void operationAllowed_normalizesString() {
        List<TracingControlProtocolViolation> v = violations();
        Map<String, Object> n = normalized();

        OperationSemanticsValidator.validateOperation(
                TracingControlProtocolKeys.OPERATION,
                TracingControlProtocolKeys.OPERATION_APPLY_RUNTIME_POLICY,
                RUNTIME_MUTATION_OPERATIONS, v, n);

        assertThat(v).isEmpty();
        assertThat(n).containsEntry(
                TracingControlProtocolKeys.OPERATION,
                TracingControlProtocolKeys.OPERATION_APPLY_RUNTIME_POLICY);
    }

    @Test
    @DisplayName("read allowlist expectedType order is deterministic: 'READ_APPLIED_STATE|READ_SCHEMA'")
    void readAllowlistExpectedTypeOrderIsDeterministic() {
        List<TracingControlProtocolViolation> v = violations();
        Map<String, Object> n = normalized();

        // APPLY_RUNTIME_POLICY is not in the read allowlist
        OperationSemanticsValidator.validateOperation(
                TracingControlProtocolKeys.OPERATION,
                TracingControlProtocolKeys.OPERATION_APPLY_RUNTIME_POLICY,
                READ_OPERATIONS, v, n);

        assertThat(v).hasSize(1);
        assertThat(v.get(0).code()).isEqualTo(TracingControlProtocolViolationCode.OPERATION_NOT_ALLOWED);
        // Deterministic after Phase 3: declaration order = READ_APPLIED_STATE|READ_SCHEMA
        assertThat(v.get(0).expectedType()).isEqualTo(
                TracingControlProtocolKeys.OPERATION_READ_APPLIED_STATE
                + "|"
                + TracingControlProtocolKeys.OPERATION_READ_SCHEMA);
    }
}
