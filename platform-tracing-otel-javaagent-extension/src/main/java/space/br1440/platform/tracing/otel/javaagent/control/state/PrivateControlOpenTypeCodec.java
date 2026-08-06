package space.br1440.platform.tracing.otel.javaagent.control.state;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;

import space.br1440.platform.tracing.otel.javaagent.control.state.PrivateControlWireTypes.WireField;

/**
 * Строгий Agent-side codec complete Category A snapshot.
 */
final class PrivateControlOpenTypeCodec {

    private static final int MAX_LOGICAL_REQUEST_BYTES = 32_768;

    /** Same family as {@link #MAX_LOGICAL_REQUEST_BYTES}; committee REQUIRE_EXPLICIT_BOUND for PRIV-R07. */
    private static final int MAX_LOGICAL_ACCEPTED_COMPLETE_BYTES = 32_768;

    DecodedPolicySnapshot decode(CompositeData request) {
        if (request == null) {
            throw rejected(PolicyMessageCode.MISSING_REQUIRED_FIELD, "request is required");
        }
        if (!PrivateControlWireTypes.REQUEST.equals(request.getCompositeType())) {
            throw rejected(PolicyMessageCode.TYPE_MISMATCH, "unexpected request CompositeType");
        }

        int version = value(request, WireField.PROTOCOL_VERSION, Integer.class);
        if (version != PrivateControlObjectNames.PROTOCOL_VERSION) {
            throw rejected(
                    PolicyMessageCode.PROTOCOL_VERSION_MISMATCH,
                    "unsupported protocol version");
        }

        String runtimeId = value(request, WireField.EXPECTED_RUNTIME_INSTANCE_ID, String.class);
        long sequence = value(request, WireField.EXPECTED_ACCEPTED_SEQUENCE, Long.class);
        String attemptId = value(request, WireField.ATTEMPT_ID, String.class);
        String authority = token(request, WireField.SOURCE_AUTHORITY);
        String trigger = token(request, WireField.APPLY_TRIGGER);
        String revision = value(request, WireField.SOURCE_REVISION, String.class);
        String candidateDigest = value(request, WireField.CANDIDATE_DIGEST, String.class);
        CompositeData policyData = value(request, WireField.RUNTIME_POLICY, CompositeData.class);

        if (sequence < 0L) {
            throw rejected(PolicyMessageCode.INVALID_VALUE, "expectedAcceptedSequence is negative");
        }
        if (!candidateDigest.matches("[0-9a-f]{64}")) {
            throw rejected(PolicyMessageCode.INVALID_VALUE, "candidateDigest is invalid");
        }
        if (!PrivateControlWireTypes.POLICY.equals(policyData.getCompositeType())) {
            throw rejected(PolicyMessageCode.TYPE_MISMATCH, "unexpected runtimePolicy CompositeType");
        }

        EffectiveTracingPolicy policy;
        try {
            policy = decodePolicy(policyData);
        } catch (IllegalArgumentException exception) {
            throw rejected(PolicyMessageCode.INVALID_VALUE, exception.getMessage());
        }
        if (logicalSize(request, policy) > MAX_LOGICAL_REQUEST_BYTES) {
            throw rejected(PolicyMessageCode.LIMIT_EXCEEDED, "logical request exceeds 32768 bytes");
        }
        if (!candidateDigest.equals(EffectiveTracingPolicyDigest.sha256(policy))) {
            throw rejected(PolicyMessageCode.INVALID_VALUE, "candidateDigest does not match policy");
        }

        try {
            return new DecodedPolicySnapshot(
                    new PolicyApplyAttempt(
                            attemptId,
                            Instant.now(),
                            PolicySourceAuthority.valueOf(authority),
                            PolicyApplyTrigger.valueOf(trigger),
                            revision),
                    new ExpectedPolicyBase(runtimeId, sequence),
                    candidateDigest,
                    policy);
        } catch (IllegalArgumentException exception) {
            throw rejected(PolicyMessageCode.INVALID_VALUE, exception.getMessage());
        }
    }

    CompositeData encode(PolicyApplyResult result, String attemptId) {
        try {
            String[] violations = result.messageCode() == PolicyMessageCode.NONE
                    ? new String[0]
                    : new String[]{result.messageCode().name()};

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put(WireField.ACCEPTED_SEQUENCE.wireName(), result.accepted().acceptedSequence());
            fields.put(WireField.ATTEMPT_ID.wireName(), attemptId);
            fields.put(WireField.MESSAGE_CODE.wireName(), result.messageCode().name());
            fields.put(WireField.OUTCOME.wireName(), result.outcome().name());
            fields.put(WireField.PAYLOAD_DIGEST.wireName(), result.payloadDigest());
            fields.put(WireField.PROTOCOL_VERSION.wireName(), PrivateControlObjectNames.PROTOCOL_VERSION);
            fields.put(WireField.RESTART_REQUIRED.wireName(), false);
            fields.put(WireField.RESTART_REQUIRED_KEYS.wireName(), new String[0]);
            fields.put(WireField.RUNTIME_INSTANCE_ID.wireName(), result.accepted().runtimeInstanceId());
            fields.put(WireField.VIOLATION_CODES.wireName(), violations);

            return new CompositeDataSupport(PrivateControlWireTypes.RESPONSE, fields);
        } catch (OpenDataException exception) {
            throw new IllegalStateException("Cannot encode private control response", exception);
        }
    }

    /**
     * Encodes one logically consistent accepted-state snapshot for PRIV-R07.
     *
     * <p>The caller must pass the {@link AcceptedPolicyView} obtained from a
     * single {@code AcceptedPolicyStateStore.current()} call (one volatile
     * {@code AtomicReference.get()}). This guarantees that
     * {@code runtimeInstanceId}, {@code acceptedSequence} and all eight
     * Category&nbsp;A policy leaves are read from the same
     * {@code AcceptedPolicyEnvelope} reference — no separate, potentially
     * inconsistent reads.
     *
     * <p>This method performs no mutation and causes no sequence change.
     */
    CompositeData encodeAcceptedComplete(AcceptedPolicyView view) {
        Objects.requireNonNull(view, "view");
        if (logicalSizeAcceptedComplete(view) > MAX_LOGICAL_ACCEPTED_COMPLETE_BYTES) {
            throw new IllegalStateException(
                    "AcceptedCompleteSnapshot logical size exceeds "
                            + MAX_LOGICAL_ACCEPTED_COMPLETE_BYTES + " bytes");
        }
        try {
            TabularData routes = encodeRouteRatios(view.samplingRouteRatios());

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put(WireField.ACCEPTED_SEQUENCE.wireName(), view.acceptedSequence());
            fields.put(WireField.RUNTIME_INSTANCE_ID.wireName(), view.runtimeInstanceId());
            fields.put(WireField.SAMPLING_ENABLED.wireName(), view.samplingEnabled());
            fields.put(WireField.SAMPLING_RATIO.wireName(), view.samplingRatio());
            fields.put(WireField.SAMPLING_ROUTE_RATIOS.wireName(), routes);
            fields.put(WireField.VALIDATION_ENABLED.wireName(), view.validationEnabled());
            fields.put(WireField.VALIDATION_STRICT.wireName(), view.validationStrict());
            fields.put(WireField.EXPORTER_ENABLED.wireName(), view.exporterEnabled());
            fields.put(WireField.TRACE_CONTROL_HEADERS_ENABLED.wireName(), view.traceControlHeadersEnabled());
            fields.put(WireField.DIAGNOSTICS_LOG_LEVEL.wireName(), view.diagnosticsLogLevel());

            return new CompositeDataSupport(PrivateControlWireTypes.ACCEPTED_COMPLETE, fields);
        } catch (OpenDataException exception) {
            throw new IllegalStateException("Cannot encode AcceptedCompleteSnapshot", exception);
        }
    }

    /**
     * Encodes route ratios into a TabularData using the existing ROUTES TabularType.
     * NavigableMap guarantees natural key order — deterministic across JVM runs.
     */
    private static TabularData encodeRouteRatios(NavigableMap<String, Double> ratios)
            throws OpenDataException {
        TabularDataSupport table = new TabularDataSupport(PrivateControlWireTypes.ROUTES);
        for (Map.Entry<String, Double> entry : ratios.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(WireField.ROUTE_PREFIX.wireName(), entry.getKey());
            row.put(WireField.ROUTE_RATIO.wireName(), entry.getValue());
            table.put(new CompositeDataSupport(PrivateControlWireTypes.ROUTE_ROW, row));
        }
        return table;
    }

    private static EffectiveTracingPolicy decodePolicy(CompositeData data) {
        TabularData routes = value(data, WireField.SAMPLING_ROUTE_RATIOS, TabularData.class);
        if (!PrivateControlWireTypes.ROUTES.equals(routes.getTabularType())) {
            throw rejected(PolicyMessageCode.TYPE_MISMATCH, "unexpected route-ratios TabularType");
        }
        if (routes.size() > EffectiveTracingPolicyValidator.MAX_ROUTE_ENTRIES) {
            throw rejected(PolicyMessageCode.LIMIT_EXCEEDED, "too many route ratios");
        }
        Map<String, Double> decodedRoutes = new TreeMap<>();
        for (Object rawRow : routes.values()) {
            CompositeData row = (CompositeData) rawRow;
            String prefix = value(row, WireField.ROUTE_PREFIX, String.class);
            Double ratio = value(row, WireField.ROUTE_RATIO, Double.class);
            if (prefix.isEmpty() || prefix.length() > 255 || containsControl(prefix)) {
                throw rejected(PolicyMessageCode.INVALID_VALUE, "invalid route prefix");
            }
            if (decodedRoutes.put(prefix, ratio) != null) {
                throw rejected(PolicyMessageCode.INVALID_VALUE, "duplicate route prefix");
            }
        }
        return EffectiveTracingPolicy.of(
                value(data, WireField.SAMPLING_ENABLED, Boolean.class),
                value(data, WireField.SAMPLING_RATIO, Double.class),
                decodedRoutes,
                value(data, WireField.VALIDATION_ENABLED, Boolean.class),
                value(data, WireField.VALIDATION_STRICT, Boolean.class),
                value(data, WireField.EXPORTER_ENABLED, Boolean.class),
                value(data, WireField.TRACE_CONTROL_HEADERS_ENABLED, Boolean.class),
                PolicyLogLevel.valueOf(token(data, WireField.DIAGNOSTICS_LOG_LEVEL)));
    }

    private static int logicalSize(
            CompositeData request,
            EffectiveTracingPolicy policy) {
        int size = 64;
        for (String key : request.getCompositeType().keySet()) {
            Object value = request.get(key);
            if (value instanceof String text) {
                size += text.getBytes(StandardCharsets.UTF_8).length;
            }
        }
        for (String routePrefix : policy.samplingRouteRatios().keySet()) {
            size += routePrefix.getBytes(StandardCharsets.UTF_8).length + 16;
        }
        return size;
    }

    private static int logicalSizeAcceptedComplete(AcceptedPolicyView view) {
        int size = 64;
        size += view.runtimeInstanceId().getBytes(StandardCharsets.UTF_8).length;
        size += view.diagnosticsLogLevel().getBytes(StandardCharsets.UTF_8).length;
        for (String routePrefix : view.samplingRouteRatios().keySet()) {
            size += routePrefix.getBytes(StandardCharsets.UTF_8).length + 16;
        }
        return size;
    }

    private static String token(CompositeData data, WireField field) {
        String value = value(data, field, String.class);
        if (!value.equals(value.trim())) {
            throw rejected(PolicyMessageCode.INVALID_VALUE, field.wireName() + " must be trimmed");
        }
        return value;
    }

    private static <T> T value(CompositeData data, WireField field, Class<T> type) {
        Object value = data.get(field.wireName());
        if (value == null) {
            throw rejected(PolicyMessageCode.MISSING_REQUIRED_FIELD, field.wireName() + " is required");
        }
        if (!type.isInstance(value)) {
            throw rejected(PolicyMessageCode.TYPE_MISMATCH, field.wireName() + " has wrong type");
        }
        return type.cast(value);
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(character -> character == 0 || Character.isISOControl(character));
    }

    private static PrivateControlDecodeException rejected(
            PolicyMessageCode code,
            String message) {
        return new PrivateControlDecodeException(code, message);
    }
}
