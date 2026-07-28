package space.br1440.platform.tracing.otel.javaagent.control.state;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Строгий Agent-side codec complete Category A snapshot.
 */
final class PrivateControlOpenTypeCodec {

    private static final int MAX_LOGICAL_REQUEST_BYTES = 32_768;

    DecodedPolicySnapshot decode(CompositeData request) {
        if (request == null) {
            throw rejected(PolicyMessageCode.MISSING_REQUIRED_FIELD, "request is required");
        }
        if (!PrivateControlWireTypes.REQUEST.equals(request.getCompositeType())) {
            throw rejected(PolicyMessageCode.TYPE_MISMATCH, "unexpected request CompositeType");
        }

        int version = value(request, "protocolVersion", Integer.class);
        if (version != PrivateControlObjectNames.PROTOCOL_VERSION) {
            throw rejected(
                    PolicyMessageCode.PROTOCOL_VERSION_MISMATCH,
                    "unsupported protocol version");
        }

        String runtimeId = value(request, "expectedRuntimeInstanceId", String.class);
        long sequence = value(request, "expectedAcceptedSequence", Long.class);
        String attemptId = value(request, "attemptId", String.class);
        String authority = token(request, "sourceAuthority");
        String trigger = token(request, "applyTrigger");
        String revision = value(request, "sourceRevision", String.class);
        String candidateDigest = value(request, "candidateDigest", String.class);
        CompositeData policyData = value(request, "runtimePolicy", CompositeData.class);

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
            return new CompositeDataSupport(
                    PrivateControlWireTypes.RESPONSE,
                    PrivateControlWireTypes.RESPONSE_KEYS,
                    new Object[]{
                            result.accepted().acceptedSequence(),
                            attemptId,
                            result.messageCode().name(),
                            result.outcome().name(),
                            result.payloadDigest(),
                            PrivateControlObjectNames.PROTOCOL_VERSION,
                            false,
                            new String[0],
                            result.accepted().runtimeInstanceId(),
                            violations
                    });
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
        try {
            TabularData routes = encodeRouteRatios(view.samplingRouteRatios());
            return new CompositeDataSupport(
                    PrivateControlWireTypes.ACCEPTED_COMPLETE,
                    PrivateControlWireTypes.ACCEPTED_COMPLETE_KEYS,
                    new Object[]{
                            view.acceptedSequence(),            // Long
                            view.runtimeInstanceId(),           // String
                            view.samplingEnabled(),             // Boolean
                            view.samplingRatio(),               // Double
                            routes,                             // TabularData
                            view.validationEnabled(),           // Boolean
                            view.validationStrict(),            // Boolean
                            view.exporterEnabled(),             // Boolean
                            view.traceControlHeadersEnabled(),  // Boolean
                            view.diagnosticsLogLevel()          // String
                    });
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
            table.put(new CompositeDataSupport(
                    PrivateControlWireTypes.ROUTE_ROW,
                    PrivateControlWireTypes.ROUTE_KEYS,
                    new Object[]{entry.getValue(), entry.getKey()}));
        }
        return table;
    }

    private static EffectiveTracingPolicy decodePolicy(CompositeData data) {
        TabularData routes = value(data, "samplingRouteRatios", TabularData.class);
        if (!PrivateControlWireTypes.ROUTES.equals(routes.getTabularType())) {
            throw rejected(PolicyMessageCode.TYPE_MISMATCH, "unexpected route-ratios TabularType");
        }
        if (routes.size() > EffectiveTracingPolicyValidator.MAX_ROUTE_ENTRIES) {
            throw rejected(PolicyMessageCode.LIMIT_EXCEEDED, "too many route ratios");
        }
        Map<String, Double> decodedRoutes = new TreeMap<>();
        for (Object rawRow : routes.values()) {
            CompositeData row = (CompositeData) rawRow;
            String prefix = value(row, "routePrefix", String.class);
            Double ratio = value(row, "ratio", Double.class);
            if (prefix.isEmpty() || prefix.length() > 255 || containsControl(prefix)) {
                throw rejected(PolicyMessageCode.INVALID_VALUE, "invalid route prefix");
            }
            if (decodedRoutes.put(prefix, ratio) != null) {
                throw rejected(PolicyMessageCode.INVALID_VALUE, "duplicate route prefix");
            }
        }
        return EffectiveTracingPolicy.of(
                value(data, "samplingEnabled", Boolean.class),
                value(data, "samplingRatio", Double.class),
                decodedRoutes,
                value(data, "validationEnabled", Boolean.class),
                value(data, "validationStrict", Boolean.class),
                value(data, "exporterEnabled", Boolean.class),
                value(data, "traceControlHeadersEnabled", Boolean.class),
                PolicyLogLevel.valueOf(token(data, "diagnosticsLogLevel")));
    }

    private static int logicalSize(
            CompositeData request,
            EffectiveTracingPolicy policy) {
        int size = 64;
        for (String key : PrivateControlWireTypes.REQUEST_KEYS) {
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

    private static String token(CompositeData data, String key) {
        String value = value(data, key, String.class);
        if (!value.equals(value.trim())) {
            throw rejected(PolicyMessageCode.INVALID_VALUE, key + " must be trimmed");
        }
        return value;
    }

    private static <T> T value(CompositeData data, String key, Class<T> type) {
        Object value = data.get(key);
        if (value == null) {
            throw rejected(PolicyMessageCode.MISSING_REQUIRED_FIELD, key + " is required");
        }
        if (!type.isInstance(value)) {
            throw rejected(PolicyMessageCode.TYPE_MISMATCH, key + " has wrong type");
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
