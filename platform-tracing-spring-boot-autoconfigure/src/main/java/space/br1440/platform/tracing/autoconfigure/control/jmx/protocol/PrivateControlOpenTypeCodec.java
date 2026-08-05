package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import space.br1440.platform.tracing.autoconfigure.control.jmx.PrivateControlLocator;

import space.br1440.platform.tracing.autoconfigure.control.policy.read.AcceptedSnapshotWithSequence;
import space.br1440.platform.tracing.autoconfigure.control.policy.CompleteTracingPolicySnapshot;

import javax.management.openmbean.ArrayType;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Independent Spring-side codec for the approved wire contract v1.
 * <p>
 * E7/P0 operations covered:
 * <ul>
 *   <li>PRIV-W01 — {@code applySnapshot}: {@link #encodeRequest(PrivateControlSnapshot)},
 *       {@link #decodeResponse(CompositeData)}</li>
 *   <li>PRIV-R07 — {@code readAcceptedComplete}: {@link #decodeCompleteWithSequence(Object)}
 *       (APPROVED by Architecture Committee 2026-07-29; explicit 32768-byte logical
 *       payload bound per {@code payloadBoundDecision=REQUIRE_EXPLICIT_BOUND})</li>
 * </ul>
 * <p>
 * Wire schema authority: {@code PrivateControlWireTypes.ACCEPTED_COMPLETE} (Agent module).
 * Any schema change MUST update both sides atomically.
 */
public final class PrivateControlOpenTypeCodec {

    // ── route-ratio subtypes (shared across request, response, and PRIV-R07) ─────

    private static final String[] ROUTE_KEYS = {"ratio", "routePrefix"};
    private static final CompositeType ROUTE_ROW = composite(
            "space.br1440.platform.tracing.control.RouteRatio.v1",
            ROUTE_KEYS,
            new OpenType<?>[]{SimpleType.DOUBLE, SimpleType.STRING});
    private static final TabularType ROUTES = table(
            "space.br1440.platform.tracing.control.RouteRatios.v1",
            ROUTE_ROW,
            new String[]{"routePrefix"});

    // ── applySnapshot request ────────────────────────────────────────────────

    private static final String[] POLICY_KEYS = {
            "diagnosticsLogLevel",
            "exporterEnabled",
            "samplingEnabled",
            "samplingRatio",
            "samplingRouteRatios",
            "traceControlHeadersEnabled",
            "validationEnabled",
            "validationStrict"
    };
    private static final CompositeType POLICY = composite(
            "space.br1440.platform.tracing.control.RuntimePolicy.v1",
            POLICY_KEYS,
            new OpenType<?>[]{
                    SimpleType.STRING,
                    SimpleType.BOOLEAN,
                    SimpleType.BOOLEAN,
                    SimpleType.DOUBLE,
                    ROUTES,
                    SimpleType.BOOLEAN,
                    SimpleType.BOOLEAN,
                    SimpleType.BOOLEAN
            });
    private static final String[] REQUEST_KEYS = {
            "applyTrigger",
            "attemptId",
            "candidateDigest",
            "expectedAcceptedSequence",
            "expectedRuntimeInstanceId",
            "protocolVersion",
            "runtimePolicy",
            "sourceAuthority",
            "sourceRevision"
    };
    private static final CompositeType REQUEST = composite(
            "space.br1440.platform.tracing.control.ApplySnapshotRequest.v1",
            REQUEST_KEYS,
            new OpenType<?>[]{
                    SimpleType.STRING,
                    SimpleType.STRING,
                    SimpleType.STRING,
                    SimpleType.LONG,
                    SimpleType.STRING,
                    SimpleType.INTEGER,
                    POLICY,
                    SimpleType.STRING,
                    SimpleType.STRING
            });

    // ── applySnapshot response ───────────────────────────────────────────────

    private static final String[] RESPONSE_KEYS = {
            "acceptedSequence",
            "attemptId",
            "messageCode",
            "outcome",
            "payloadDigest",
            "protocolVersion",
            "restartRequired",
            "restartRequiredKeys",
            "runtimeInstanceId",
            "violationCodes"
    };
    private static final CompositeType RESPONSE = composite(
            "space.br1440.platform.tracing.control.ApplySnapshotResponse.v1",
            RESPONSE_KEYS,
            new OpenType<?>[]{
                    SimpleType.LONG,
                    SimpleType.STRING,
                    SimpleType.STRING,
                    SimpleType.STRING,
                    SimpleType.STRING,
                    SimpleType.INTEGER,
                    SimpleType.BOOLEAN,
                    arrayOfStrings(),
                    SimpleType.STRING,
                    arrayOfStrings()
            });

    // ── readAcceptedComplete response (PRIV-R07) ──────────────────────────────
    //
    // Field order and type name are AUTHORITATIVE from Agent PrivateControlWireTypes:
    //   type name:  "AcceptedCompleteSnapshot/v1"
    //   fields:     acceptedSequence, runtimeInstanceId, samplingEnabled,
    //               samplingRatio, samplingRouteRatios, validationEnabled,
    //               validationStrict, exporterEnabled, traceControlHeadersEnabled,
    //               diagnosticsLogLevel
    // Do NOT change without updating Agent PrivateControlWireTypes.ACCEPTED_COMPLETE.

    static final String ACCEPTED_COMPLETE_TYPE_NAME = "AcceptedCompleteSnapshot/v1";

    private static final String[] COMPLETE_KEYS = {
            "acceptedSequence",           // Long  >= 0
            "runtimeInstanceId",          // String non-empty
            "samplingEnabled",            // Boolean
            "samplingRatio",              // Double [0.0, 1.0]
            "samplingRouteRatios",        // TabularData (ROUTES)
            "validationEnabled",          // Boolean
            "validationStrict",           // Boolean
            "exporterEnabled",            // Boolean
            "traceControlHeadersEnabled", // Boolean
            "diagnosticsLogLevel"         // String closed: OFF|ERROR|WARN|INFO|DEBUG|TRACE
    };

    /**
     * Mirrors the Agent {@code PolicyLogLevel} enum — the sole closed set accepted for
     * {@code diagnosticsLogLevel} on the wire in either direction.
     */
    private static final Set<String> VALID_LOG_LEVELS =
            Set.of("OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE");

    /**
     * Mirrors the Agent {@code EffectiveTracingPolicyValidator} bounds
     * ({@code MAX_ROUTE_ENTRIES}, {@code MAX_ROUTE_PREFIX_LENGTH}) enforced when the Agent
     * itself builds an {@code EffectiveTracingPolicy}. {@code MAX_ROUTE_PREFIX_LENGTH} is a
     * {@link String#length()} bound — Java UTF-16 code units, not UTF-8 bytes.
     */
    private static final int MAX_ROUTE_ENTRIES = 100;
    private static final int MAX_ROUTE_PREFIX_LENGTH = 255;

    /** Same family as Agent applySnapshot MAX_LOGICAL_REQUEST_BYTES; PRIV-R07 REQUIRE_EXPLICIT_BOUND. */
    private static final int MAX_LOGICAL_ACCEPTED_COMPLETE_BYTES = 32_768;

    private static final CompositeType COMPLETE_RESPONSE = composite(
            ACCEPTED_COMPLETE_TYPE_NAME,
            COMPLETE_KEYS,
            new OpenType<?>[]{
                    SimpleType.LONG,      // acceptedSequence
                    SimpleType.STRING,    // runtimeInstanceId
                    SimpleType.BOOLEAN,   // samplingEnabled
                    SimpleType.DOUBLE,    // samplingRatio
                    ROUTES,               // samplingRouteRatios
                    SimpleType.BOOLEAN,   // validationEnabled
                    SimpleType.BOOLEAN,   // validationStrict
                    SimpleType.BOOLEAN,   // exporterEnabled
                    SimpleType.BOOLEAN,   // traceControlHeadersEnabled
                    SimpleType.STRING     // diagnosticsLogLevel
            });

    // ── encode ────────────────────────────────────────────────────────────────

    /**
     * Builds the single-argument {@code applySnapshot} wire request from application-level
     * pieces, computing {@code candidateDigest} via {@link CandidateDigest} (Agent-algorithm
     * mirror, verified by a cross-module parity test).
     * <p>
     * There is exactly ONE Open-Type argument on the wire — {@code request} — matching the
     * Agent {@code MBeanOperationInfo} for {@code applySnapshot}. Callers must never pass
     * additional positional arguments (e.g. sequence, source) alongside this composite; every
     * field the Agent needs is already inside it.
     */
    public CompositeData encodeApplyRequest(
            String expectedRuntimeInstanceId,
            long expectedAcceptedSequence,
            String attemptId,
            String sourceAuthority,
            String applyTrigger,
            String sourceRevision,
            CompleteTracingPolicySnapshot policy) {
        String candidateDigest = CandidateDigest.sha256(policy);
        PrivateControlSnapshot snapshot = new PrivateControlSnapshot(
                expectedRuntimeInstanceId,
                expectedAcceptedSequence,
                attemptId,
                sourceAuthority,
                applyTrigger,
                sourceRevision,
                candidateDigest,
                policy.samplingEnabled(),
                policy.samplingRatio(),
                policy.samplingRouteRatios(),
                policy.validationEnabled(),
                policy.validationStrict(),
                policy.exporterEnabled(),
                policy.traceControlHeadersEnabled(),
                policy.diagnosticsLogLevel());
        return encodeRequest(snapshot);
    }

    public CompositeData encodeRequest(PrivateControlSnapshot snapshot) {
        try {
            TabularDataSupport routes = new TabularDataSupport(ROUTES);
            for (Map.Entry<String, Double> route : new TreeMap<>(
                    snapshot.samplingRouteRatios()).entrySet()) {
                routes.put(new CompositeDataSupport(
                        ROUTE_ROW,
                        ROUTE_KEYS,
                        new Object[]{route.getValue(), route.getKey()}));
            }
            CompositeData policy = new CompositeDataSupport(
                    POLICY,
                    POLICY_KEYS,
                    new Object[]{
                            snapshot.diagnosticsLogLevel(),
                            snapshot.exporterEnabled(),
                            snapshot.samplingEnabled(),
                            snapshot.samplingRatio(),
                            routes,
                            snapshot.traceControlHeadersEnabled(),
                            snapshot.validationEnabled(),
                            snapshot.validationStrict()
                    });
            return new CompositeDataSupport(
                    REQUEST,
                    REQUEST_KEYS,
                    new Object[]{
                            snapshot.applyTrigger(),
                            snapshot.attemptId(),
                            snapshot.candidateDigest(),
                            snapshot.expectedAcceptedSequence(),
                            snapshot.expectedRuntimeInstanceId(),
                            PrivateControlLocator.PROTOCOL_VERSION,
                            policy,
                            snapshot.sourceAuthority(),
                            snapshot.sourceRevision()
                    });
        } catch (OpenDataException exception) {
            throw new IllegalArgumentException("Cannot encode private control request", exception);
        }
    }

    // ── decode: applySnapshot response ──────────────────────────────────────

    public PrivateControlResponse decodeResponse(CompositeData response) {
        if (response == null || !RESPONSE.equals(response.getCompositeType())) {
            throw new IllegalArgumentException("Unexpected private control response CompositeType");
        }
        return new PrivateControlResponse(
                (String) response.get("outcome"),
                (String) response.get("attemptId"),
                (String) response.get("runtimeInstanceId"),
                (Long) response.get("acceptedSequence"),
                (String) response.get("payloadDigest"),
                (String) response.get("messageCode"),
                (Boolean) response.get("restartRequired"),
                (String[]) response.get("restartRequiredKeys"),
                (String[]) response.get("violationCodes"));
    }

    // ── decode: readAcceptedComplete (PRIV-R07) ─────────────────────────────────

    /**
     * Decodes the Agent {@code readAcceptedComplete} wire response.
     * <p>
     * Contract: Agent MUST return {@code CompositeData} typed as
     * {@value ACCEPTED_COMPLETE_TYPE_NAME}; any other type or {@code null}
     * is a hard contract violation.
     *
     * @param raw the raw object returned by {@code MBeanServer.invoke}
     * @return decoded {@link AcceptedSnapshotWithSequence} carrying
     *         acceptedSequence, runtimeInstanceId, and the full 8-leaf snapshot
     * @throws IllegalArgumentException if {@code raw} is null, not a
     *         {@code CompositeData}, or has an unexpected {@code CompositeType}
     */
    public AcceptedSnapshotWithSequence decodeCompleteWithSequence(Object raw) {
        if (!(raw instanceof CompositeData cd)) {
            throw new IllegalArgumentException(
                    "readAcceptedComplete: expected CompositeData, got "
                    + (raw == null ? "null" : raw.getClass().getName()));
        }
        if (!COMPLETE_RESPONSE.equals(cd.getCompositeType())) {
            throw new IllegalArgumentException(
                    "readAcceptedComplete: unexpected CompositeType "
                    + cd.getCompositeType().getTypeName()
                    + "; expected " + COMPLETE_RESPONSE.getTypeName());
        }
        if (logicalSizeAcceptedComplete(cd) > MAX_LOGICAL_ACCEPTED_COMPLETE_BYTES) {
            throw new IllegalArgumentException(
                    "readAcceptedComplete: logical payload exceeds "
                            + MAX_LOGICAL_ACCEPTED_COMPLETE_BYTES + " bytes");
        }

        // This decoder is a cross-process, cross-classloader trust boundary: the Agent is
        // expected to only ever send values within its own EffectiveTracingPolicyValidator
        // bounds, but Spring must not assume that. Every field is independently re-validated
        // here — a wire payload is never trusted merely because it typed-matched the
        // CompositeType and "should" have come from the Agent.
        long   acceptedSequence     = requireField(cd, "acceptedSequence", Long.class);
        String runtimeInstanceId    = requireField(cd, "runtimeInstanceId", String.class);
        boolean samplingEnabled     = requireField(cd, "samplingEnabled", Boolean.class);
        double  samplingRatio       = requireField(cd, "samplingRatio", Double.class);
        boolean validationEnabled   = requireField(cd, "validationEnabled", Boolean.class);
        boolean validationStrict    = requireField(cd, "validationStrict", Boolean.class);
        boolean exporterEnabled     = requireField(cd, "exporterEnabled", Boolean.class);
        boolean traceControlHeaders = requireField(cd, "traceControlHeadersEnabled", Boolean.class);
        String  diagnosticsLogLevel = requireField(cd, "diagnosticsLogLevel", String.class);

        if (acceptedSequence < 0L) {
            throw new IllegalArgumentException(
                    "readAcceptedComplete: acceptedSequence must be >= 0, got " + acceptedSequence);
        }
        if (runtimeInstanceId.isBlank()) {
            throw new IllegalArgumentException("readAcceptedComplete: runtimeInstanceId must not be blank");
        }
        requireValidRatio(samplingRatio, "samplingRatio");
        if (!VALID_LOG_LEVELS.contains(diagnosticsLogLevel)) {
            throw new IllegalArgumentException(
                    "readAcceptedComplete: diagnosticsLogLevel is not a recognized level: "
                    + diagnosticsLogLevel);
        }

        Map<String, Double> routeRatios =
                decodeRouteRatios(requireField(cd, "samplingRouteRatios", TabularData.class));

        CompleteTracingPolicySnapshot snapshot = CompleteTracingPolicySnapshot.builder()
                .samplingEnabled(samplingEnabled)
                .samplingRatio(samplingRatio)
                .samplingRouteRatios(routeRatios)
                .validationEnabled(validationEnabled)
                .validationStrict(validationStrict)
                .exporterEnabled(exporterEnabled)
                .traceControlHeadersEnabled(traceControlHeaders)
                .diagnosticsLogLevel(diagnosticsLogLevel)
                .build();
        return new AcceptedSnapshotWithSequence(acceptedSequence, runtimeInstanceId, snapshot);
    }

    private static int logicalSizeAcceptedComplete(CompositeData cd) {
        int size = 64;
        Object runtimeId = cd.get("runtimeInstanceId");
        if (runtimeId instanceof String text) {
            size += text.getBytes(StandardCharsets.UTF_8).length;
        }
        Object logLevel = cd.get("diagnosticsLogLevel");
        if (logLevel instanceof String text) {
            size += text.getBytes(StandardCharsets.UTF_8).length;
        }
        Object routes = cd.get("samplingRouteRatios");
        if (routes instanceof TabularData tabular) {
            for (Object rawRow : tabular.values()) {
                if (rawRow instanceof CompositeData row) {
                    Object prefix = row.get("routePrefix");
                    if (prefix instanceof String text) {
                        size += text.getBytes(StandardCharsets.UTF_8).length + 16;
                    }
                }
            }
        }
        return size;
    }

    private static <T> T requireField(CompositeData cd, String key, Class<T> type) {
        Object value = cd.get(key);
        if (value == null) {
            throw new IllegalArgumentException("readAcceptedComplete: field '" + key + "' is null");
        }
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException(
                    "readAcceptedComplete: field '" + key + "' has unexpected type "
                    + value.getClass().getName());
        }
        return type.cast(value);
    }

    private static void requireValidRatio(double ratio, String fieldName) {
        if (Double.isNaN(ratio) || Double.isInfinite(ratio)) {
            throw new IllegalArgumentException(
                    "readAcceptedComplete: " + fieldName + " must be finite, got " + ratio);
        }
        if (ratio < 0.0 || ratio > 1.0) {
            throw new IllegalArgumentException(
                    "readAcceptedComplete: " + fieldName + " must be within [0.0, 1.0], got " + ratio);
        }
    }

    private static Map<String, Double> decodeRouteRatios(TabularData tabular) {
        if (tabular == null || tabular.isEmpty()) return Collections.emptyMap();
        if (tabular.size() > MAX_ROUTE_ENTRIES) {
            throw new IllegalArgumentException(
                    "readAcceptedComplete: samplingRouteRatios has " + tabular.size()
                    + " entries, exceeding the maximum of " + MAX_ROUTE_ENTRIES);
        }
        Map<String, Double> result = new LinkedHashMap<>();
        for (Object value : tabular.values()) {
            CompositeData row = (CompositeData) value;
            String prefix = requireField(row, "routePrefix", String.class);
            Double ratio  = requireField(row, "ratio", Double.class);
            if (prefix.isEmpty() || prefix.length() > MAX_ROUTE_PREFIX_LENGTH) {
                throw new IllegalArgumentException(
                        "readAcceptedComplete: route prefix length " + prefix.length()
                        + " is invalid (must be 1.." + MAX_ROUTE_PREFIX_LENGTH + " UTF-16 code units)");
            }
            // Do not echo route prefixes in exception text (P5.5 disclosure canary).
            requireValidRatio(ratio, "samplingRouteRatios.entry");
            if (result.put(prefix, ratio) != null) {
                throw new IllegalArgumentException(
                        "readAcceptedComplete: duplicate route prefix in samplingRouteRatios");
            }
        }
        return Collections.unmodifiableMap(result);
    }

    // ── records ────────────────────────────────────────────────────────────────

    public record PrivateControlSnapshot(
            String expectedRuntimeInstanceId,
            long expectedAcceptedSequence,
            String attemptId,
            String sourceAuthority,
            String applyTrigger,
            String sourceRevision,
            String candidateDigest,
            boolean samplingEnabled,
            double samplingRatio,
            Map<String, Double> samplingRouteRatios,
            boolean validationEnabled,
            boolean validationStrict,
            boolean exporterEnabled,
            boolean traceControlHeadersEnabled,
            String diagnosticsLogLevel) {

        public PrivateControlSnapshot {
            samplingRouteRatios = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(
                            samplingRouteRatios,
                            "samplingRouteRatios")));
        }
    }

    public record PrivateControlResponse(
            String outcome,
            String attemptId,
            String runtimeInstanceId,
            long acceptedSequence,
            String payloadDigest,
            String messageCode,
            boolean restartRequired,
            String[] restartRequiredKeys,
            String[] violationCodes) {

        public PrivateControlResponse {
            restartRequiredKeys = restartRequiredKeys.clone();
            violationCodes = violationCodes.clone();
        }

        @Override
        public String[] restartRequiredKeys() {
            return restartRequiredKeys.clone();
        }

        @Override
        public String[] violationCodes() {
            return violationCodes.clone();
        }
    }

    // ── static helpers ─────────────────────────────────────────────────────────────

    private static CompositeType composite(String name, String[] keys, OpenType<?>[] types) {
        try {
            return new CompositeType(name, name, keys, keys, types);
        } catch (OpenDataException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static TabularType table(String name, CompositeType row, String[] indexes) {
        try {
            return new TabularType(name, name, row, indexes);
        } catch (OpenDataException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static ArrayType<?> arrayOfStrings() {
        try {
            return new ArrayType<>(1, SimpleType.STRING);
        } catch (OpenDataException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
