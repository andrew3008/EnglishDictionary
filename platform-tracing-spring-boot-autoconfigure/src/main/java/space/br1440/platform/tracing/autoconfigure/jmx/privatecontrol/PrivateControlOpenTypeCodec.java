package space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol;

import space.br1440.platform.tracing.autoconfigure.configuration.AcceptedSnapshotWithSequence;
import space.br1440.platform.tracing.autoconfigure.configuration.CompleteTracingPolicySnapshot;

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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Independent Spring-side codec for the approved wire contract v1.
 * <p>
 * E7/P0 operations covered:
 * <ul>
 *   <li>PRIV-W01 — {@code applySnapshot}: {@link #encodeRequest(PrivateControlSnapshot)},
 *       {@link #decodeResponse(CompositeData)}</li>
 *   <li>PRIV-R07 — {@code readAcceptedComplete}: {@link #decodeCompleteWithSequence(Object)}
 *       (NEW, Variant A — Spring-side decoding only; Agent-side implementation pending
 *       committee approval and Agent PR)</li>
 * </ul>
 */
final class PrivateControlOpenTypeCodec {

    // ── route-ratio subtypes ───────────────────────────────────────────────────

    private static final String[] ROUTE_KEYS = {"ratio", "routePrefix"};
    private static final CompositeType ROUTE_ROW = composite(
            "space.br1440.platform.tracing.control.RouteRatio.v1",
            ROUTE_KEYS,
            new OpenType<?>[]{SimpleType.DOUBLE, SimpleType.STRING});
    private static final TabularType ROUTES = table(
            "space.br1440.platform.tracing.control.RouteRatios.v1",
            ROUTE_ROW,
            new String[]{"routePrefix"});

    // ── applySnapshot request ──────────────────────────────────────────────────

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

    // ── applySnapshot response ─────────────────────────────────────────────────

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

    // ── readAcceptedComplete response (PRIV-R07, Variant A) ───────────────────
    //
    // Wire contract: Agent returns CompositeData with these exact keys.
    // Spring-side only; Agent-side implementation requires separate committee approval.
    // Keys are frozen once Agent PR is merged — do not reorder.

    private static final String[] COMPLETE_KEYS = {
            "acceptedSequence",
            "diagnosticsLogLevel",
            "exporterEnabled",
            "runtimeInstanceId",
            "samplingEnabled",
            "samplingRatio",
            "samplingRouteRatios",
            "traceControlHeadersEnabled",
            "validationEnabled",
            "validationStrict"
    };
    private static final CompositeType COMPLETE_RESPONSE = composite(
            "space.br1440.platform.tracing.control.AcceptedCompleteResponse.v1",
            COMPLETE_KEYS,
            new OpenType<?>[]{
                    SimpleType.LONG,      // acceptedSequence
                    SimpleType.STRING,    // diagnosticsLogLevel
                    SimpleType.BOOLEAN,   // exporterEnabled
                    SimpleType.STRING,    // runtimeInstanceId
                    SimpleType.BOOLEAN,   // samplingEnabled
                    SimpleType.DOUBLE,    // samplingRatio
                    ROUTES,               // samplingRouteRatios
                    SimpleType.BOOLEAN,   // traceControlHeadersEnabled
                    SimpleType.BOOLEAN,   // validationEnabled
                    SimpleType.BOOLEAN    // validationStrict
            });

    // ── encode ─────────────────────────────────────────────────────────────────

    CompositeData encodeRequest(PrivateControlSnapshot snapshot) {
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

    // ── decode: applySnapshot response ────────────────────────────────────────

    PrivateControlResponse decodeResponse(CompositeData response) {
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

    // ── decode: readAcceptedComplete (PRIV-R07) ────────────────────────────────

    /**
     * Decodes the Agent {@code readAcceptedComplete} wire response.
     * <p>
     * Contract: Agent MUST return {@code CompositeData} typed with
     * {@code AcceptedCompleteResponse.v1}; any other type or {@code null}
     * is a hard contract violation and throws
     * {@link IllegalArgumentException} — caller maps this to
     * {@link space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicyContractViolationException}.
     *
     * @param raw the raw object returned by {@code MBeanServer.invoke}
     * @return decoded {@link AcceptedSnapshotWithSequence} containing acceptedSequence
     *         and the full {@link CompleteTracingPolicySnapshot}
     * @throws IllegalArgumentException if {@code raw} is not a correctly-typed
     *         {@code CompositeData}
     */
    AcceptedSnapshotWithSequence decodeCompleteWithSequence(Object raw) {
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

        long acceptedSequence     = (Long)    cd.get("acceptedSequence");
        String diagnosticsLogLevel = (String)  cd.get("diagnosticsLogLevel");
        boolean exporterEnabled    = (Boolean) cd.get("exporterEnabled");
        boolean samplingEnabled    = (Boolean) cd.get("samplingEnabled");
        double  samplingRatio      = (Double)  cd.get("samplingRatio");
        boolean traceControlHeaders= (Boolean) cd.get("traceControlHeadersEnabled");
        boolean validationEnabled  = (Boolean) cd.get("validationEnabled");
        boolean validationStrict   = (Boolean) cd.get("validationStrict");

        Map<String, Double> routeRatios = decodeRouteRatios((TabularData) cd.get("samplingRouteRatios"));

        CompleteTracingPolicySnapshot snapshot = new CompleteTracingPolicySnapshot(
                samplingEnabled,
                samplingRatio,
                routeRatios,
                validationEnabled,
                validationStrict,
                exporterEnabled,
                traceControlHeaders,
                diagnosticsLogLevel
        );
        return new AcceptedSnapshotWithSequence(acceptedSequence, snapshot);
    }

    private static Map<String, Double> decodeRouteRatios(TabularData tabular) {
        if (tabular == null || tabular.isEmpty()) return Collections.emptyMap();
        Map<String, Double> result = new LinkedHashMap<>();
        for (Object value : tabular.values()) {
            CompositeData row = (CompositeData) value;
            String prefix = (String) row.get("routePrefix");
            Double ratio  = (Double) row.get("ratio");
            if (prefix != null && ratio != null) {
                result.put(prefix, ratio);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    // ── records ────────────────────────────────────────────────────────────────

    record PrivateControlSnapshot(
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

        PrivateControlSnapshot {
            samplingRouteRatios = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(
                            samplingRouteRatios,
                            "samplingRouteRatios")));
        }
    }

    record PrivateControlResponse(
            String outcome,
            String attemptId,
            String runtimeInstanceId,
            long acceptedSequence,
            String payloadDigest,
            String messageCode,
            boolean restartRequired,
            String[] restartRequiredKeys,
            String[] violationCodes) {

        PrivateControlResponse {
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

    // ── static helpers ─────────────────────────────────────────────────────────

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
