package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import java.util.Set;
import javax.management.openmbean.ArrayType;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularType;

final class PrivateControlWireTypes {

    private static final String[] ROUTE_KEYS = {"ratio", "routePrefix"};
    static final CompositeType ROUTE_ROW = composite(
            "space.br1440.platform.tracing.control.RouteRatio.v1",
            ROUTE_KEYS,
            new OpenType<?>[]{SimpleType.DOUBLE, SimpleType.STRING});
    static final TabularType ROUTES = table(
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
    static final CompositeType POLICY = composite(
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
    static final String[] REQUEST_KEYS = {
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
    static final CompositeType REQUEST = composite(
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

    static final String[] RESPONSE_KEYS = {
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
    static final CompositeType RESPONSE = composite(
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

    // ── readAcceptedComplete response ──────────────────────────────

    static final String ACCEPTED_COMPLETE_TYPE_NAME = "AcceptedCompleteSnapshot/v1";

    private static final String[] COMPLETE_KEYS = {
            "acceptedSequence",
            "runtimeInstanceId",
            "samplingEnabled",
            "samplingRatio",
            "samplingRouteRatios",
            "validationEnabled",
            "validationStrict",
            "exporterEnabled",
            "traceControlHeadersEnabled",
            "diagnosticsLogLevel"
    };

    static final Set<String> VALID_LOG_LEVELS = Set.of("OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE");

    static final int MAX_ROUTE_ENTRIES = 100;
    static final int MAX_ROUTE_PREFIX_LENGTH = 255;

    static final int MAX_LOGICAL_ACCEPTED_COMPLETE_BYTES = 32_768;

    static final CompositeType COMPLETE_RESPONSE = composite(
            ACCEPTED_COMPLETE_TYPE_NAME,
            COMPLETE_KEYS,
            new OpenType<?>[]{
                    SimpleType.LONG,
                    SimpleType.STRING,
                    SimpleType.BOOLEAN,
                    SimpleType.DOUBLE,
                    ROUTES,
                    SimpleType.BOOLEAN,
                    SimpleType.BOOLEAN,
                    SimpleType.BOOLEAN,
                    SimpleType.BOOLEAN,
                    SimpleType.STRING
            });

    private PrivateControlWireTypes() {
    }

    static CompositeType composite(String name, String[] keys, OpenType<?>[] types) {
        try {
            return new CompositeType(name, name, keys, keys, types);
        } catch (OpenDataException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @SuppressWarnings("SameParameterValue")
    static TabularType table(String name, CompositeType row, String[] indexes) {
        try {
            return new TabularType(name, name, row, indexes);
        } catch (OpenDataException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    static ArrayType<?> arrayOfStrings() {
        try {
            return new ArrayType<>(1, SimpleType.STRING);
        } catch (OpenDataException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
