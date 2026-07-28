package space.br1440.platform.tracing.otel.javaagent.control.state;

import javax.management.openmbean.ArrayType;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularType;

/**
 * Exact Open-Type schema версии 1 на Agent-side границе.
 */
final class PrivateControlWireTypes {

    static final String[] ROUTE_KEYS = {"ratio", "routePrefix"};
    static final CompositeType ROUTE_ROW = composite(
            "space.br1440.platform.tracing.control.RouteRatio.v1",
            ROUTE_KEYS,
            new OpenType<?>[]{SimpleType.DOUBLE, SimpleType.STRING});
    static final TabularType ROUTES = table(
            "space.br1440.platform.tracing.control.RouteRatios.v1",
            ROUTE_ROW,
            new String[]{"routePrefix"});

    static final String[] POLICY_KEYS = {
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

    /**
     * Canonical field names for readAcceptedComplete (PRIV-R07).
     * Order is fixed and tested — do not reorder.
     */
    static final String[] ACCEPTED_COMPLETE_KEYS = {
            "acceptedSequence",            // Long  >= 0
            "runtimeInstanceId",           // String non-empty
            "samplingEnabled",             // Boolean
            "samplingRatio",               // Double [0.0, 1.0]
            "samplingRouteRatios",         // TabularData reuses ROUTES
            "validationEnabled",           // Boolean
            "validationStrict",            // Boolean
            "exporterEnabled",             // Boolean
            "traceControlHeadersEnabled",  // Boolean
            "diagnosticsLogLevel"          // String closed: DEBUG|INFO|WARN|ERROR
    };

    /**
     * Versioned CompositeType for readAcceptedComplete response (PRIV-R07).
     * Type name is exact and tested by Spring decoder.
     */
    static final CompositeType ACCEPTED_COMPLETE = composite(
            "AcceptedCompleteSnapshot/v1",
            ACCEPTED_COMPLETE_KEYS,
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

    static void initialize() {
        if (REQUEST.keySet().size() != REQUEST_KEYS.length
                || POLICY.keySet().size() != POLICY_KEYS.length
                || ACCEPTED_COMPLETE.keySet().size() != ACCEPTED_COMPLETE_KEYS.length) {
            throw new IllegalStateException("Private control Open-Type schema is inconsistent");
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
