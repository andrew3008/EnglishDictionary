package space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import javax.management.openmbean.ArrayType;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;

/**
 * Независимый Spring-side codec утверждённого wire contract v1.
 *
 * <p>Internal delivery seam for Config Server / DEV adapters. Not a supported application API.
 */
public final class PrivateControlOpenTypeCodec {

    private static final String[] ROUTE_KEYS = {"ratio", "routePrefix"};
    private static final CompositeType ROUTE_ROW = composite(
            "space.br1440.platform.tracing.control.RouteRatio.v1",
            ROUTE_KEYS,
            new OpenType<?>[]{SimpleType.DOUBLE, SimpleType.STRING});
    private static final TabularType ROUTES = table(
            "space.br1440.platform.tracing.control.RouteRatios.v1",
            ROUTE_ROW,
            new String[]{"routePrefix"});
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

    public CompositeData encodeResponse(PrivateControlResponse response) {
        Objects.requireNonNull(response, "response");
        try {
            return new CompositeDataSupport(
                    RESPONSE,
                    RESPONSE_KEYS,
                    new Object[]{
                            response.acceptedSequence(),
                            response.attemptId(),
                            response.messageCode(),
                            response.outcome(),
                            response.payloadDigest(),
                            PrivateControlLocator.PROTOCOL_VERSION,
                            response.restartRequired(),
                            response.restartRequiredKeys(),
                            response.runtimeInstanceId(),
                            response.violationCodes()
                    });
        } catch (OpenDataException exception) {
            throw new IllegalArgumentException("Cannot encode private control response", exception);
        }
    }

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
