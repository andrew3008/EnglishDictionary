package space.br1440.platform.tracing.api.control.protocol.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocol;
import space.br1440.platform.tracing.api.control.protocol.result.TracingControlProtocolValidationResult;
import space.br1440.platform.tracing.api.control.protocol.result.TracingControlProtocolViolation;
import space.br1440.platform.tracing.api.control.protocol.schema.TracingControlProtocolKeys;
import space.br1440.platform.tracing.api.control.protocol.schema.TracingControlProtocolTypes;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TracingControlProtocolValidatorTest
 *
 * Phase 0.0 — Schema Key Mapping (derived from TracingControlProtocolSchema.buildV1Fields())
 * ──────────────────────────────────────────────────────────────────────────────────────────
 * Role              | Key constant                                   | Wire key string              | Type              | Category
 * ──────────────────|────────────────────────────────────────────────|──────────────────────────────|───────────────────|──────────────────
 * contractVersion   | Keys.CONTRACT_VERSION                          | "contractVersion"            | INTEGER           | ENVELOPE (required)
 * operation         | Keys.OPERATION                                 | "operation"                  | STRING            | ENVELOPE (required)
 * ENVELOPE key      | Keys.SOURCE                                    | "source"                     | STRING            | ENVELOPE (optional)
 * DOUBLE ratio key  | Keys.SAMPLING_RATIO                            | "sampling.ratio"             | DOUBLE            | RUNTIME_POLICY
 * routeRatios key   | Keys.SAMPLING_ROUTE_RATIOS                     | "sampling.routeRatios"       | ROUTE_RATIOS_MAP  | RUNTIME_POLICY
 * BOOLEAN key       | Keys.SAMPLING_KILL_SWITCH_ENABLED              | "sampling.killSwitch.enabled"| BOOLEAN           | RUNTIME_POLICY
 * STRING_ARRAY key  | Keys.SAMPLING_FORCE_HEADER_VALUES              | "sampling.forceHeader.values"| STRING_ARRAY      | RUNTIME_POLICY
 * STRING_ARRAY key2 | Keys.SAMPLING_DROP_PATH_PREFIXES               | "sampling.dropPathPrefixes"  | STRING_ARRAY      | RUNTIME_POLICY
 * validation.mode   | Keys.VALIDATION_MODE                           | "validation.mode"            | STRING            | RUNTIME_POLICY
 * RUNTIME_POLICY    | Keys.SCRUBBING_ENABLED                         | "scrubbing.enabled"          | BOOLEAN           | RUNTIME_POLICY
 * DIAGNOSTIC (STR)  | Keys.DIAGNOSTICS_REQUEST_ID                    | "diagnostics.requestId"      | STRING            | DIAGNOSTIC
 * LONG key          | Keys.DIAGNOSTICS_TIMESTAMP                     | "diagnostics.timestamp"      | LONG              | DIAGNOSTIC
 * STARTUP_TOPOLOGY  | Keys.TOPOLOGY_EXPORTER_ENDPOINT                | "exporter.endpoint"          | STRING            | STARTUP_TOPOLOGY
 * INTEGER (topology)| Keys.TOPOLOGY_EXPORTER_QUEUE_SIZE              | "exporter.queue.size"        | INTEGER           | STARTUP_TOPOLOGY
 *
 * INTEGER key for coercion tests: Keys.TOPOLOGY_EXPORTER_QUEUE_SIZE is STARTUP_TOPOLOGY and will be rejected by
 * category policy before type validation. There is NO non-topology, non-envelope INTEGER field in v1 schema.
 * The contractVersion field is INTEGER but is handled by a special branch, not the generic type switch.
 * Therefore INTEGER coercion tests (tests 8-9) use contractVersion as the INTEGER field value carrier
 * and verify normalised output — contractVersion accepts Long-in-range (→ Integer major) per
 * TracingControlProtocolVersion.parse(Long). Tests 8-9 characterize this path.
 *
 * contractVersion = null: TracingControlProtocolVersion.parse(null) returns Optional.empty()
 * → the "parse empty" branch fires → INVALID_VALUE, reason "invalid contractVersion".
 * This is confirmed by reading the parse() switch: case null → return Optional.empty().
 * Test 17 records and asserts this observed behavior.
 */
@DisplayName("TracingControlProtocolValidator: payload validation")
class TracingControlProtocolValidatorTest {

    private static final TracingControlProtocolValidator VALIDATOR = TracingControlProtocol.current().validator();

    // ─────────────────────────────────────────────────────────────────────────────
    // Existing helpers (unchanged)
    // ─────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> minimalRuntimePayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        payload.put(TracingControlProtocolKeys.OPERATION, TracingControlProtocolKeys.OPERATION_APPLY_RUNTIME_POLICY);
        return payload;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Phase 0 helper: minimal runtime payload as LinkedHashMap with deterministic
    // insertion order — required for any test that asserts violation ordering.
    // ─────────────────────────────────────────────────────────────────────────────

    private static LinkedHashMap<String, Object> orderedMinimalRuntimePayload() {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        payload.put(TracingControlProtocolKeys.OPERATION, TracingControlProtocolKeys.OPERATION_APPLY_RUNTIME_POLICY);
        return payload;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Existing 24 tests (preserved verbatim)
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("valid minimal runtime policy payload")
    void validMinimalRuntimePolicy() {
        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(minimalRuntimePayload());

        assertThat(result.valid()).isTrue();
        assertThat(result.violations()).isEmpty();
        assertThat(result.normalizedPayload())
                .containsEntry(TracingControlProtocolKeys.CONTRACT_VERSION, 1)
                .containsEntry(TracingControlProtocolKeys.OPERATION, TracingControlProtocolKeys.OPERATION_APPLY_RUNTIME_POLICY);
    }

    @Test
    @DisplayName("valid sampling ratio boundaries 0.0 and 1.0")
    void validSamplingRatioBoundaries() {
        Map<String, Object> low = minimalRuntimePayload();
        low.put(TracingControlProtocolKeys.SAMPLING_RATIO, 0.0);
        assertThat(VALIDATOR.validateRuntimePolicy(low).valid()).isTrue();

        Map<String, Object> high = minimalRuntimePayload();
        high.put(TracingControlProtocolKeys.SAMPLING_RATIO, 1.0);
        assertThat(VALIDATOR.validateRuntimePolicy(high).valid()).isTrue();
    }

    @Test
    @DisplayName("invalid sampling ratio below 0.0")
    void invalidSamplingRatioBelowZero() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, -0.01);

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> TracingControlProtocolKeys.SAMPLING_RATIO.equals(v.key())
                        && v.code() == TracingControlProtocolViolationCode.TYPE_MISMATCH);
    }

    @Test
    @DisplayName("invalid sampling ratio above 1.0")
    void invalidSamplingRatioAboveOne() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 1.01);

        assertThat(VALIDATOR.validateRuntimePolicy(payload).valid()).isFalse();
    }

    @Test
    @DisplayName("invalid sampling ratio type")
    void invalidSamplingRatioType() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, "0.5");

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations().get(0).expectedType()).isEqualTo(TracingControlProtocolTypes.DOUBLE.name());
        assertThat(result.violations().get(0).code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
    }

    @Test
    @DisplayName("unknown key rejected (strict v1)")
    void unknownKeyRejected() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put("unknownKey", "value");

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> "unknownKey".equals(v.key())
                        && v.reason().contains("unknown key")
                        && v.code() == TracingControlProtocolViolationCode.UNKNOWN_KEY);
    }

    @Test
    @DisplayName("custom DTO value rejected")
    void customDtoRejected() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.SOURCE, new Object());

        assertThat(VALIDATOR.validateRuntimePolicy(payload).valid()).isFalse();
    }

    @Test
    @DisplayName("enum value rejected")
    void enumValueRejected() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.VALIDATION_MODE, Thread.State.RUNNABLE);

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> v.reason().contains("enum instance rejected"));
    }

    @Test
    @DisplayName("non-string map key rejected")
    void nonStringMapKeyRejected() {
        Map<Object, Object> badMap = new HashMap<>();
        badMap.put(1, "value");
        badMap.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        badMap.put(TracingControlProtocolKeys.OPERATION, TracingControlProtocolKeys.OPERATION_APPLY_RUNTIME_POLICY);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) (Map<?, ?>) badMap;

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> "<map>".equals(v.key()));
    }

    @Test
    @DisplayName("unsupported contractVersion rejected")
    void unsupportedContractVersionRejected() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 2);

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> TracingControlProtocolKeys.CONTRACT_VERSION.equals(v.key())
                        && v.code() == TracingControlProtocolViolationCode.UNSUPPORTED_VERSION);
    }

    @Test
    @DisplayName("malformed contractVersion rejected as INVALID_VALUE")
    void malformedContractVersionRejected() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, "abc");

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> TracingControlProtocolKeys.CONTRACT_VERSION.equals(v.key())
                        && v.code() == TracingControlProtocolViolationCode.INVALID_VALUE);
    }

    @Test
    @DisplayName("contractVersion accepts String '1'")
    void contractVersionStringAccepted() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, "1");

        assertThat(VALIDATOR.validateRuntimePolicy(payload).valid()).isTrue();
    }

    @Test
    @DisplayName("topology field rejected for runtime apply")
    void topologyFieldRejectedForRuntimeApply() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.TOPOLOGY_EXPORTER_ENDPOINT, "http://collector:4317");

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> TracingControlProtocolKeys.TOPOLOGY_EXPORTER_ENDPOINT.equals(v.key())
                        && v.reason().contains("topology"));
    }

    @Test
    @DisplayName("String[] accepted for force header values")
    void stringArrayAccepted() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.SAMPLING_FORCE_HEADER_VALUES, new String[]{"on", "true"});

        assertThat(VALIDATOR.validateRuntimePolicy(payload).valid()).isTrue();
    }

    @Test
    @DisplayName("List rejected where String[] required")
    void listRejectedForStringArray() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.SAMPLING_DROP_PATH_PREFIXES, List.of("/health"));

        assertThat(VALIDATOR.validateRuntimePolicy(payload).valid()).isFalse();
    }

    @Test
    @DisplayName("null required key rejected")
    void nullRequiredKeyRejected() {
        Map<String, Object> payload = new HashMap<>();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> TracingControlProtocolKeys.OPERATION.equals(v.key())
                        && v.reason().contains("required")
                        && v.code() == TracingControlProtocolViolationCode.MISSING_REQUIRED_KEY);
    }

    @Test
    @DisplayName("optional policy fields allowed when valid")
    void optionalPolicyFieldsAllowed() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 0.25);
        payload.put(TracingControlProtocolKeys.SCRUBBING_ENABLED, Boolean.TRUE);
        payload.put(TracingControlProtocolKeys.VALIDATION_MODE, "WARN");
        payload.put(TracingControlProtocolKeys.SOURCE, "config-server");

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isTrue();
        assertThat(result.normalizedPayload())
                .containsEntry(TracingControlProtocolKeys.SAMPLING_RATIO, 0.25)
                .containsEntry(TracingControlProtocolKeys.SCRUBBING_ENABLED, true);
    }

    @Test
    @DisplayName("routeRatios map validates per-route bounds")
    void routeRatiosMapValidated() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS, Map.of("/api", 0.5, "/admin", 1.0));

        assertThat(VALIDATOR.validateRuntimePolicy(payload).valid()).isTrue();

        Map<String, Object> invalid = minimalRuntimePayload();
        invalid.put(TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS, Map.of("/api", 2.0));

        assertThat(VALIDATOR.validateRuntimePolicy(invalid).valid()).isFalse();
    }

    @Test
    @DisplayName("validateReadRequest accepts READ operation with diagnostics")
    void validateReadRequest() {
        Map<String, Object> payload = new HashMap<>();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        payload.put(TracingControlProtocolKeys.OPERATION, TracingControlProtocolKeys.OPERATION_READ_APPLIED_STATE);
        payload.put(TracingControlProtocolKeys.DIAGNOSTICS_REQUEST_ID, "req-1");

        assertThat(VALIDATOR.validateReadRequest(payload).valid()).isTrue();
    }

    @Test
    @DisplayName("validateReadRequest rejects runtime policy mutation fields")
    void readRequestRejectsPolicyFields() {
        Map<String, Object> payload = new HashMap<>();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        payload.put(TracingControlProtocolKeys.OPERATION, TracingControlProtocolKeys.OPERATION_READ_SCHEMA);
        payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 0.5);

        assertThat(VALIDATOR.validateReadRequest(payload).valid()).isFalse();
    }

    @Test
    @DisplayName("VALIDATE_RUNTIME_POLICY operation accepted")
    void validateRuntimePolicyOperation() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.OPERATION, TracingControlProtocolKeys.OPERATION_VALIDATE_RUNTIME_POLICY);

        assertThat(VALIDATOR.validateRuntimePolicy(payload).valid()).isTrue();
    }

    @Test
    @DisplayName("null payload returns MISSING_REQUIRED_KEY for every required key")
    void nullPayload() {
        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(null);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).hasSize(2);
        assertThat(result.violations())
                .allMatch(v -> v.code() == TracingControlProtocolViolationCode.MISSING_REQUIRED_KEY);
    }

    @Test
    @DisplayName("empty payload returns MISSING_REQUIRED_KEY for contractVersion and operation")
    void emptyPayload() {
        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(Map.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).hasSize(2);
        assertThat(result.violations())
                .extracting(v -> v.key())
                .containsExactlyInAnyOrder(
                        TracingControlProtocolKeys.CONTRACT_VERSION,
                        TracingControlProtocolKeys.OPERATION);
    }

    @Test
    @DisplayName("known key with null value is TYPE_MISMATCH")
    void knownKeyNullValueTypeMismatch() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.SOURCE, null);

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> TracingControlProtocolKeys.SOURCE.equals(v.key())
                        && v.code() == TracingControlProtocolViolationCode.TYPE_MISMATCH);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Phase 0 — characterization tests 1–23 (single-defect)
    // ─────────────────────────────────────────────────────────────────────────────

    // ── Test 1 ──────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[char-01] operation not String → TYPE_MISMATCH")
    void validateRuntimePolicy_operationNotString_returnsTypeMismatch() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.OPERATION, 42); // Integer, not String

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();

        TracingControlProtocolViolation v = result.violations().stream()
                .filter(x -> TracingControlProtocolKeys.OPERATION.equals(x.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected violation for key 'operation'"));

        assertThat(v.code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
        assertThat(v.reason()).isEqualTo("operation must be String");
        assertThat(v.expectedType()).isEqualTo("String");
    }

    // ── Test 2 ──────────────────────────────────────────────────────────────────
    // Phase 0 NOTE: The validator uses Set.of(...) for allowedOperations whose
    // iteration order is JVM-randomised. We therefore do NOT assert the exact
    // String.join("|", ...) string; instead we assert that expectedType contains
    // all runtime-mutation operation strings. Exact order will be locked in Phase 3
    // after the allowlist is made deterministic (Variant A).
    @Test
    @DisplayName("[char-02] operation valid but wrong for validateRuntimePolicy → OPERATION_NOT_ALLOWED")
    void validateRuntimePolicy_operationNotInAllowlist_returnsOperationNotAllowed() {
        Map<String, Object> payload = minimalRuntimePayload();
        // READ_APPLIED_STATE is a read operation — not in RUNTIME_MUTATION_OPERATIONS
        payload.put(TracingControlProtocolKeys.OPERATION,
                TracingControlProtocolKeys.OPERATION_READ_APPLIED_STATE);

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();

        TracingControlProtocolViolation v = result.violations().stream()
                .filter(x -> TracingControlProtocolKeys.OPERATION.equals(x.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected violation for key 'operation'"));

        assertThat(v.code()).isEqualTo(TracingControlProtocolViolationCode.OPERATION_NOT_ALLOWED);
        assertThat(v.reason()).isEqualTo("unsupported operation for this validation entry point");
        // Phase 0: assert containment, not exact join order
        assertThat(v.expectedType())
                .contains(TracingControlProtocolKeys.OPERATION_APPLY_RUNTIME_POLICY)
                .contains(TracingControlProtocolKeys.OPERATION_VALIDATE_RUNTIME_POLICY);
        assertThat(v.actualType()).isEqualTo(TracingControlProtocolKeys.OPERATION_READ_APPLIED_STATE);
    }

    // ── Test 3 ──────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[char-03] validateReadRequest rejects APPLY_RUNTIME_POLICY operation → OPERATION_NOT_ALLOWED")
    void validateReadRequest_runtimePolicyOperation_returnsOperationNotAllowed() {
        Map<String, Object> payload = new HashMap<>();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        payload.put(TracingControlProtocolKeys.OPERATION,
                TracingControlProtocolKeys.OPERATION_APPLY_RUNTIME_POLICY);

        TracingControlProtocolValidationResult result = VALIDATOR.validateReadRequest(payload);

        assertThat(result.valid()).isFalse();

        TracingControlProtocolViolation v = result.violations().stream()
                .filter(x -> TracingControlProtocolKeys.OPERATION.equals(x.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected violation for key 'operation'"));

        assertThat(v.code()).isEqualTo(TracingControlProtocolViolationCode.OPERATION_NOT_ALLOWED);
        assertThat(v.reason()).isEqualTo("unsupported operation for this validation entry point");
        // Phase 0: containment assert only
        assertThat(v.expectedType())
                .contains(TracingControlProtocolKeys.OPERATION_READ_APPLIED_STATE)
                .contains(TracingControlProtocolKeys.OPERATION_READ_SCHEMA);
    }

    // ── Test 4 ──────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[char-04] validateRuntimePolicy rejects READ_SCHEMA operation → OPERATION_NOT_ALLOWED")
    void validateReadRequest_readOperationOnRuntimeEntry_returnsOperationNotAllowed() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.OPERATION,
                TracingControlProtocolKeys.OPERATION_READ_SCHEMA);

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();

        TracingControlProtocolViolation v = result.violations().stream()
                .filter(x -> TracingControlProtocolKeys.OPERATION.equals(x.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected violation for key 'operation'"));

        assertThat(v.code()).isEqualTo(TracingControlProtocolViolationCode.OPERATION_NOT_ALLOWED);
        assertThat(v.reason()).isEqualTo("unsupported operation for this validation entry point");
    }

    // ── Test 5 ──────────────────────────────────────────────────────────────────
    // STARTUP_TOPOLOGY key: TracingControlProtocolKeys.TOPOLOGY_EXPORTER_ENDPOINT
    // ("exporter.endpoint", STRING, STARTUP_TOPOLOGY) — confirmed from schema.
    @Test
    @DisplayName("[char-05] STARTUP_TOPOLOGY field in runtime payload → OPERATION_NOT_ALLOWED")
    void validateRuntimePolicy_topologyCategoryField_returnsOperationNotAllowed() {
        Map<String, Object> payload = minimalRuntimePayload();
        // TOPOLOGY_EXPORTER_ENDPOINT is STARTUP_TOPOLOGY category
        payload.put(TracingControlProtocolKeys.TOPOLOGY_EXPORTER_ENDPOINT, "http://collector:4317");

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();

        TracingControlProtocolViolation v = result.violations().stream()
                .filter(x -> TracingControlProtocolKeys.TOPOLOGY_EXPORTER_ENDPOINT.equals(x.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected violation for key 'exporter.endpoint'"));

        assertThat(v.code()).isEqualTo(TracingControlProtocolViolationCode.OPERATION_NOT_ALLOWED);
        assertThat(v.reason()).isEqualTo("startup topology field rejected for wire control path");
        assertThat(v.expectedType()).isEqualTo("runtime policy or envelope key");
    }

    // ── Test 6 ──────────────────────────────────────────────────────────────────
    // validation.mode key: TracingControlProtocolKeys.VALIDATION_MODE
    // ("validation.mode", STRING, RUNTIME_POLICY)
    @Test
    @DisplayName("[char-06] unknown validation.mode value → TYPE_MISMATCH")
    void validateRuntimePolicy_unknownValidationMode_returnsTypeMismatch() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.VALIDATION_MODE, "LOUD");

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();

        TracingControlProtocolViolation v = result.violations().stream()
                .filter(x -> TracingControlProtocolKeys.VALIDATION_MODE.equals(x.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected violation for key 'validation.mode'"));

        assertThat(v.code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
        assertThat(v.reason()).isEqualTo("unknown validation.mode wire value");
        assertThat(v.expectedType()).isEqualTo("STRICT|WARN|DISABLED");
        assertThat(v.actualType()).isEqualTo("LOUD");
    }

    // ── Test 7 ──────────────────────────────────────────────────────────────────
    // Characterizes: case-insensitive acceptance AND non-canonicalization.
    // "warn" (lowercase) must be accepted AND normalised as "warn" (not "WARN").
    @Test
    @DisplayName("[char-07] validation.mode 'warn' (lowercase) accepted; returned as 'warn', not 'WARN'")
    void validateRuntimePolicy_validationModeWarnLowercase_isAccepted() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.VALIDATION_MODE, "warn");

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isTrue();
        // Characterize non-canonicalization: must be "warn" not "WARN"
        assertThat(result.normalizedPayload())
                .containsEntry(TracingControlProtocolKeys.VALIDATION_MODE, "warn");
    }

    // ── Tests 8–9 — INTEGER field coercion ──────────────────────────────────────
    //
    // Schema analysis: the only INTEGER-typed fields in v1 are:
    //   contractVersion (ENVELOPE, required, special-cased branch — not through generic switch)
    //   TOPOLOGY_EXPORTER_QUEUE_SIZE (INTEGER, STARTUP_TOPOLOGY — rejected by category before type)
    //   TOPOLOGY_QUEUE_SIZE (INTEGER, STARTUP_TOPOLOGY — rejected by category before type)
    //
    // There is NO non-topology, non-contractVersion INTEGER field in v1 schema.
    // Therefore tests 8–9 use the contractVersion branch which delegates to
    // TracingControlProtocolVersion.parse(Long), which performs the Integer-range check
    // and narrows to intValue(). This characterizes the Integer/Long coercion path that
    // Phase 1 extractions must preserve.
    //
    // contractVersion = Long(1) → parse returns Version(1) → supported → normalised as Integer 1.
    // contractVersion = Long.MAX_VALUE → parse returns Optional.empty() → INVALID_VALUE.

    @Test
    @DisplayName("[char-08] contractVersion as Long in int range → normalised to Integer major")
    void validateRuntimePolicy_integerKeyGivenLongInRange_coercesSuccessfully() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1L); // Long, in int range

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isTrue();
        // contractVersion.major() returns int → boxed to Integer
        Object normalised = result.normalizedPayload().get(TracingControlProtocolKeys.CONTRACT_VERSION);
        assertThat(normalised).isInstanceOf(Integer.class);
        assertThat(normalised).isEqualTo(1);
    }

    @Test
    @DisplayName("[char-09] contractVersion as Long out of int range → INVALID_VALUE (parse fails)")
    void validateRuntimePolicy_integerKeyGivenLongOutOfRange_returnsTypeMismatch() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, Long.MAX_VALUE);

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        // parse(Long.MAX_VALUE) returns Optional.empty() → INVALID_VALUE
        assertThat(result.valid()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> TracingControlProtocolKeys.CONTRACT_VERSION.equals(v.key())
                        && v.code() == TracingControlProtocolViolationCode.INVALID_VALUE
                        && "invalid contractVersion".equals(v.reason()));
    }

    // ── Test 10 — LONG field coercion ────────────────────────────────────────────
    // LONG key: TracingControlProtocolKeys.DIAGNOSTICS_TIMESTAMP
    // ("diagnostics.timestamp", LONG, DIAGNOSTIC)
    @Test
    @DisplayName("[char-10] LONG field given Integer → coerces to Long")
    void validateRuntimePolicy_longKeyGivenIntegerValue_coercesSuccessfully() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.DIAGNOSTICS_TIMESTAMP, 42); // Integer, not Long

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isTrue();
        Object normalised = result.normalizedPayload().get(TracingControlProtocolKeys.DIAGNOSTICS_TIMESTAMP);
        assertThat(normalised).isInstanceOf(Long.class);
        assertThat(normalised).isEqualTo(42L);
    }

    // ── Test 11 — STRING_ARRAY null element ──────────────────────────────────────
    // STRING_ARRAY key: TracingControlProtocolKeys.SAMPLING_FORCE_HEADER_VALUES
    // ("sampling.forceHeader.values", STRING_ARRAY, RUNTIME_POLICY)
    @Test
    @DisplayName("[char-11] String[] with null element → TYPE_MISMATCH")
    void validateRuntimePolicy_stringArrayWithNullElement_returnsTypeMismatch() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.SAMPLING_FORCE_HEADER_VALUES,
                new String[]{"ok", null, "also-ok"});

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();

        TracingControlProtocolViolation v = result.violations().stream()
                .filter(x -> TracingControlProtocolKeys.SAMPLING_FORCE_HEADER_VALUES.equals(x.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected violation for key 'sampling.forceHeader.values'"));

        assertThat(v.code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
        assertThat(v.reason()).isEqualTo("String[] must not contain null elements");
        assertThat(v.expectedType()).isEqualTo("String[]");
        assertThat(v.actualType()).isEqualTo("null element");
    }

    // ── Tests 12–16 — routeRatios shape errors ───────────────────────────────────
    // routeRatios key: TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS
    // ("sampling.routeRatios", ROUTE_RATIOS_MAP, RUNTIME_POLICY)

    @Test
    @DisplayName("[char-12] routeRatios value is not a Map → TYPE_MISMATCH")
    void validateRuntimePolicy_routeRatiosNotAMap_returnsTypeMismatch() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS, "not-a-map");

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();

        TracingControlProtocolViolation v = result.violations().stream()
                .filter(x -> TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS.equals(x.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected violation for key 'sampling.routeRatios'"));

        assertThat(v.code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
        assertThat(v.reason()).isEqualTo("invalid wire type");
        assertThat(v.expectedType()).isEqualTo(TracingControlProtocolTypes.ROUTE_RATIOS_MAP.name());
    }

    @Test
    @DisplayName("[char-13] routeRatios with non-String key → TYPE_MISMATCH")
    void validateRuntimePolicy_routeRatiosNonStringKey_returnsTypeMismatch() {
        Map<Object, Object> innerMap = new LinkedHashMap<>();
        innerMap.put(42, 0.5); // Integer key, not String

        Map<String, Object> payload = minimalRuntimePayload();
        @SuppressWarnings("unchecked")
        Map<String, Object> cast = (Map<String, Object>) (Map<?, ?>) innerMap;
        payload.put(TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS, cast);

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();

        TracingControlProtocolViolation v = result.violations().stream()
                .filter(x -> TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS.equals(x.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected violation for key 'sampling.routeRatios'"));

        assertThat(v.code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
        assertThat(v.reason()).isEqualTo("routeRatios map keys must be String");
        assertThat(v.expectedType()).isEqualTo("Map<String,Double>");
    }

    @Test
    @DisplayName("[char-14] routeRatios entry value is enum instance → TYPE_MISMATCH")
    void validateRuntimePolicy_routeRatiosEntryIsEnum_returnsTypeMismatch() {
        Map<String, Object> innerMap = new LinkedHashMap<>();
        innerMap.put("/api", Thread.State.RUNNABLE); // enum value

        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS, innerMap);

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();

        TracingControlProtocolViolation v = result.violations().stream()
                .filter(x -> TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS.equals(x.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected violation for key 'sampling.routeRatios'"));

        assertThat(v.code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
        assertThat(v.reason()).isEqualTo("enum instance rejected in routeRatios");
        assertThat(v.expectedType()).isEqualTo("Double");
    }

    @Test
    @DisplayName("[char-15] routeRatios entry value is wrong type (String) → TYPE_MISMATCH")
    void validateRuntimePolicy_routeRatiosEntryWrongType_returnsTypeMismatch() {
        Map<String, Object> innerMap = new LinkedHashMap<>();
        innerMap.put("/api", "not-a-number"); // String, not Double-coercible

        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS, innerMap);

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();
        // violation key is synthetic: "sampling.routeRatios./api"
        assertThat(result.violations())
                .anyMatch(v -> v.code() == TracingControlProtocolViolationCode.TYPE_MISMATCH
                        && "invalid wire type".equals(v.reason())
                        && TracingControlProtocolTypes.DOUBLE.name().equals(v.expectedType()));
    }

    @Test
    @DisplayName("[char-16] routeRatios entry ratio out of [0,1] → TYPE_MISMATCH; key is synthetic")
    void validateRuntimePolicy_routeRatiosEntryOutOfRange_returnsTypeMismatch() {
        Map<String, Object> innerMap = new LinkedHashMap<>();
        innerMap.put("/api", 1.5); // out of range

        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS, innerMap);

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();

        // Synthetic key: parentKey + "." + routeKey = "sampling.routeRatios./api"
        String expectedSyntheticKey = TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS + "." + "/api";

        TracingControlProtocolViolation v = result.violations().stream()
                .filter(x -> expectedSyntheticKey.equals(x.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Expected violation with synthetic key '" + expectedSyntheticKey + "'"));

        assertThat(v.code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
        assertThat(v.reason()).isEqualTo("ratio must be in [0.0, 1.0]");
        assertThat(v.expectedType()).isEqualTo("[0.0, 1.0]");
        assertThat(v.actualType()).isEqualTo("1.5");
    }

    // ── Test 17 — contractVersion = null (characterize, do not assume) ───────────
    //
    // From TracingControlProtocolVersion.parse(): case null → return Optional.empty()
    // → parse returns empty → the "malformed" branch fires → INVALID_VALUE.
    // This is NOT MISSING_REQUIRED_KEY: contractVersion key is present in the payload
    // (payload.containsKey("contractVersion") == true), value is null.
    // The contractVersion branch runs before the generic null-value check.
    // Observed behavior: INVALID_VALUE, reason "invalid contractVersion".
    @Test
    @DisplayName("[char-17] contractVersion key present with null value → INVALID_VALUE (current behavior)")
    void validateRuntimePolicy_contractVersionNullValue_currentBehavior() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, null);

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        // Characterize: parse(null) → Optional.empty() → INVALID_VALUE
        assertThat(result.valid()).isFalse();

        TracingControlProtocolViolation v = result.violations().stream()
                .filter(x -> TracingControlProtocolKeys.CONTRACT_VERSION.equals(x.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected violation for key 'contractVersion'"));

        assertThat(v.code()).isEqualTo(TracingControlProtocolViolationCode.INVALID_VALUE);
        assertThat(v.reason()).isEqualTo("invalid contractVersion");
        assertThat(v.expectedType()).isEqualTo("Integer");
    }

    // ── Test 18 ──────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[char-18] malformed contractVersion string → INVALID_VALUE")
    void validateRuntimePolicy_contractVersionMalformed_returnsInvalidValue() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, "not-a-version");

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();

        TracingControlProtocolViolation v = result.violations().stream()
                .filter(x -> TracingControlProtocolKeys.CONTRACT_VERSION.equals(x.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected violation for key 'contractVersion'"));

        assertThat(v.code()).isEqualTo(TracingControlProtocolViolationCode.INVALID_VALUE);
        assertThat(v.reason()).isEqualTo("invalid contractVersion");
        assertThat(v.expectedType()).isEqualTo("Integer");
    }

    // ── Test 19 ──────────────────────────────────────────────────────────────────
    // Version 2 parses successfully (is an Integer) but is not in BY_MAJOR registry
    // (only major=1 is supported). → UNSUPPORTED_VERSION.
    @Test
    @DisplayName("[char-19] parsed but unsupported contractVersion → UNSUPPORTED_VERSION")
    void validateRuntimePolicy_contractVersionUnsupported_returnsUnsupportedVersion() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 2); // parseable, unsupported

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();

        TracingControlProtocolViolation v = result.violations().stream()
                .filter(x -> TracingControlProtocolKeys.CONTRACT_VERSION.equals(x.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected violation for key 'contractVersion'"));

        assertThat(v.code()).isEqualTo(TracingControlProtocolViolationCode.UNSUPPORTED_VERSION);
        assertThat(v.reason()).isEqualTo("unsupported contractVersion");
        // expectedType = String.valueOf(TracingControlProtocol.current().version().major()) = "1"
        assertThat(v.expectedType()).isEqualTo("1");
    }

    // ── Test 20 — DOUBLE ratio field out of range ─────────────────────────────────
    // DOUBLE ratio key: TracingControlProtocolKeys.SAMPLING_RATIO
    @Test
    @DisplayName("[char-20] sampling.ratio out of [0,1] → TYPE_MISMATCH; boundaries 0.0 and 1.0 are valid")
    void validateRuntimePolicy_doubleRatioOutOfRange_returnsTypeMismatch() {
        // Out of range below
        Map<String, Object> belowPayload = minimalRuntimePayload();
        belowPayload.put(TracingControlProtocolKeys.SAMPLING_RATIO, -0.1);
        TracingControlProtocolValidationResult belowResult = VALIDATOR.validateRuntimePolicy(belowPayload);
        assertThat(belowResult.valid()).isFalse();
        TracingControlProtocolViolation below = belowResult.violations().stream()
                .filter(x -> TracingControlProtocolKeys.SAMPLING_RATIO.equals(x.key()))
                .findFirst()
                .orElseThrow();
        assertThat(below.code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
        assertThat(below.reason()).isEqualTo("ratio must be in [0.0, 1.0]");
        assertThat(below.expectedType()).isEqualTo("[0.0, 1.0]");
        assertThat(below.actualType()).isEqualTo("-0.1");

        // Out of range above
        Map<String, Object> abovePayload = minimalRuntimePayload();
        abovePayload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 1.1);
        TracingControlProtocolValidationResult aboveResult = VALIDATOR.validateRuntimePolicy(abovePayload);
        assertThat(aboveResult.valid()).isFalse();
        TracingControlProtocolViolation above = aboveResult.violations().stream()
                .filter(x -> TracingControlProtocolKeys.SAMPLING_RATIO.equals(x.key()))
                .findFirst()
                .orElseThrow();
        assertThat(above.code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
        assertThat(above.reason()).isEqualTo("ratio must be in [0.0, 1.0]");
        assertThat(above.actualType()).isEqualTo("1.1");

        // Boundary 0.0 — valid
        Map<String, Object> zeroPayload = minimalRuntimePayload();
        zeroPayload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 0.0);
        assertThat(VALIDATOR.validateRuntimePolicy(zeroPayload).valid()).isTrue();

        // Boundary 1.0 — valid
        Map<String, Object> onePayload = minimalRuntimePayload();
        onePayload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 1.0);
        assertThat(VALIDATOR.validateRuntimePolicy(onePayload).valid()).isTrue();
    }

    // ── Test 21 — discard-on-invalid (highest-risk invariant) ────────────────────
    @Test
    @DisplayName("[char-21] one valid field + one invalid field → invalid result, normalized payload empty")
    void validateRuntimePolicy_oneValidOneInvalid_discardsNormalizedPayload() {
        // source is a valid STRING field; sampling.ratio has wrong type → invalid
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.SOURCE, "my-service");         // valid STRING
        payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, "bad-type");   // wrong type

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();
        // All normalized data must be discarded — normalizedPayload is Map.of() per invalid()
        assertThat(result.normalizedPayload()).isEmpty();
    }

    // ── Test 22 ──────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[char-22] unknown key in payload → UNKNOWN_KEY with exact reason string")
    void validateRuntimePolicy_unknownKey_returnsUnknownKey() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put("totally.unknown.key", "value");

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();

        TracingControlProtocolViolation v = result.violations().stream()
                .filter(x -> "totally.unknown.key".equals(x.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected violation for key 'totally.unknown.key'"));

        assertThat(v.code()).isEqualTo(TracingControlProtocolViolationCode.UNKNOWN_KEY);
        assertThat(v.reason()).isEqualTo("unknown key rejected (strict v1)");
        assertThat(v.expectedType()).isEqualTo("known wire key");
    }

    // ── Test 23 ──────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[char-23] non-String map key → TYPE_MISMATCH; violation key is '<map>'")
    void validateRuntimePolicy_nonStringMapKey_returnsTypeMismatchWithMapKeyPlaceholder() {
        Map<Object, Object> badMap = new LinkedHashMap<>();
        badMap.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        badMap.put(TracingControlProtocolKeys.OPERATION, TracingControlProtocolKeys.OPERATION_APPLY_RUNTIME_POLICY);
        badMap.put(99, "integer-keyed-entry"); // non-String key

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) (Map<?, ?>) badMap;

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();

        TracingControlProtocolViolation v = result.violations().stream()
                .filter(x -> "<map>".equals(x.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected violation with key '<map>'"));

        assertThat(v.code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
        assertThat(v.reason()).isEqualTo("map keys must be String");
        assertThat(v.expectedType()).isEqualTo("String");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Phase 0 — multi-defect tests (24–26)
    // All use LinkedHashMap to make payload entry iteration order deterministic.
    // ─────────────────────────────────────────────────────────────────────────────

    // ── Test 24 ──────────────────────────────────────────────────────────────────
    // Payload (insertion order via LinkedHashMap):
    //   contractVersion = 1          → valid
    //   operation = APPLY_...        → valid
    //   totally.unknown.key          → UNKNOWN_KEY (first entry after envelope)
    //   sampling.ratio = "bad"       → TYPE_MISMATCH
    // Then required-key sweep: both keys present → no MISSING_REQUIRED_KEY.
    // Expected violation order: UNKNOWN_KEY first, TYPE_MISMATCH second.
    @Test
    @DisplayName("[char-24] multiple violations in iteration order: UNKNOWN_KEY then TYPE_MISMATCH")
    void multipleFieldViolations_correctOrderAndCodes() {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        payload.put(TracingControlProtocolKeys.OPERATION,
                TracingControlProtocolKeys.OPERATION_APPLY_RUNTIME_POLICY);
        payload.put("totally.unknown.key", "value");          // → UNKNOWN_KEY
        payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, "bad-type"); // → TYPE_MISMATCH

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();

        List<TracingControlProtocolViolation> violations = result.violations();
        // Must have at least 2 violations
        assertThat(violations).hasSizeGreaterThanOrEqualTo(2);

        // First violation (iteration order): unknown key
        assertThat(violations.get(0).code()).isEqualTo(TracingControlProtocolViolationCode.UNKNOWN_KEY);
        assertThat(violations.get(0).key()).isEqualTo("totally.unknown.key");

        // Second violation: type mismatch on sampling.ratio
        assertThat(violations.get(1).code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
        assertThat(violations.get(1).key()).isEqualTo(TracingControlProtocolKeys.SAMPLING_RATIO);
        assertThat(violations.get(1).reason()).isEqualTo("invalid wire type");
    }

    // ── Test 25 ──────────────────────────────────────────────────────────────────
    // Both contractVersion (malformed) and operation (non-String) are invalid.
    // Insertion order: contractVersion first → INVALID_VALUE; operation second → TYPE_MISMATCH.
    @Test
    @DisplayName("[char-25] malformed contractVersion AND operation not String → both violations present")
    void contractVersionAndOperationBothInvalid_bothViolationsPresent() {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, "not-a-version"); // INVALID_VALUE
        payload.put(TracingControlProtocolKeys.OPERATION, 42);                     // TYPE_MISMATCH

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isFalse();

        List<TracingControlProtocolViolation> violations = result.violations();
        // Both violations must be present (required-key sweep may add more for 'operation' if
        // non-String value does not satisfy containsKey — but OPERATION key IS present)
        assertThat(violations).anyMatch(v ->
                TracingControlProtocolKeys.CONTRACT_VERSION.equals(v.key())
                        && v.code() == TracingControlProtocolViolationCode.INVALID_VALUE
                        && "invalid contractVersion".equals(v.reason()));

        assertThat(violations).anyMatch(v ->
                TracingControlProtocolKeys.OPERATION.equals(v.key())
                        && v.code() == TracingControlProtocolViolationCode.TYPE_MISMATCH
                        && "operation must be String".equals(v.reason()));

        // First violation (insertion order) is contractVersion
        assertThat(violations.get(0).key()).isEqualTo(TracingControlProtocolKeys.CONTRACT_VERSION);
        assertThat(violations.get(0).code()).isEqualTo(TracingControlProtocolViolationCode.INVALID_VALUE);
    }

    // ── Test 26 ──────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[char-26] null payload → MISSING_REQUIRED_KEY for each required key")
    void nullPayload_missingRequiredKeysForEachRequiredKey() {
        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(null);

        assertThat(result.valid()).isFalse();
        // v1 requires contractVersion and operation
        assertThat(result.violations()).hasSize(2);
        assertThat(result.violations())
                .allMatch(v -> v.code() == TracingControlProtocolViolationCode.MISSING_REQUIRED_KEY
                        && "required key missing".equals(v.reason())
                        && "absent".equals(v.actualType()));
        assertThat(result.violations())
                .extracting(TracingControlProtocolViolation::key)
                .containsExactlyInAnyOrder(
                        TracingControlProtocolKeys.CONTRACT_VERSION,
                        TracingControlProtocolKeys.OPERATION);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Phase 0 — additional coverage tests 27–29
    // ─────────────────────────────────────────────────────────────────────────────

    // ── Test 27 — DOUBLE coercion from Float ─────────────────────────────────────
    @Test
    @DisplayName("[char-27] sampling.ratio given as Float → coerces to Double")
    void doubleRatioFieldGivenFloat_coercesToDouble() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 0.5f); // Float

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isTrue();
        Object normalised = result.normalizedPayload().get(TracingControlProtocolKeys.SAMPLING_RATIO);
        assertThat(normalised).isInstanceOf(Double.class);
        // Float 0.5f is exactly representable; double value should equal 0.5
        assertThat((Double) normalised).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
    }

    // ── Test 28 — DOUBLE coercion from Integer ────────────────────────────────────
    @Test
    @DisplayName("[char-28] sampling.ratio given as Integer → coerces to Double")
    void doubleRatioFieldGivenInteger_coercesToDouble() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 1); // Integer (value 1 = 1.0, within bounds)

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        assertThat(result.valid()).isTrue();
        Object normalised = result.normalizedPayload().get(TracingControlProtocolKeys.SAMPLING_RATIO);
        assertThat(normalised).isInstanceOf(Double.class);
        assertThat((Double) normalised).isEqualTo(1.0);
    }

    // ── Test 29 — routeRatios empty map (characterize, do not assume) ─────────────
    //
    // An empty Map<String,Double> passes all validation gates:
    //   - value instanceof Map → OK
    //   - inner loop body never executes (no entries to validate)
    //   - returns an empty LinkedHashMap<String,Double>
    // Observed behavior from source: valid result, normalized routeRatios = empty LinkedHashMap.
    @Test
    @DisplayName("[char-29] routeRatios empty map → current behavior (valid, normalized to empty LinkedHashMap)")
    void routeRatiosEmptyMap_currentBehavior() {
        Map<String, Object> payload = minimalRuntimePayload();
        payload.put(TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS, new LinkedHashMap<String, Double>());

        TracingControlProtocolValidationResult result = VALIDATOR.validateRuntimePolicy(payload);

        // Characterize: empty map passes all checks → valid
        assertThat(result.valid()).isTrue();
        Object normalised = result.normalizedPayload().get(TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS);
        assertThat(normalised).isInstanceOf(Map.class);
        assertThat((Map<?, ?>) normalised).isEmpty();
    }
}
