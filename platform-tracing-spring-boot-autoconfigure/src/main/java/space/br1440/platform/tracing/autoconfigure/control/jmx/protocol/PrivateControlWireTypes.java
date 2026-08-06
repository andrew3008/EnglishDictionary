package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import lombok.experimental.UtilityClass;

import java.util.Set;
import java.util.function.Supplier;

import javax.management.openmbean.ArrayType;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularType;

@UtilityClass
final class PrivateControlWireTypes {

    enum WireField {
        ROUTE_RATIO("ratio", SimpleType.DOUBLE),
        ROUTE_PREFIX("routePrefix", SimpleType.STRING),
        DIAGNOSTICS_LOG_LEVEL("diagnosticsLogLevel", SimpleType.STRING),
        EXPORTER_ENABLED("exporterEnabled", SimpleType.BOOLEAN),
        SAMPLING_ENABLED("samplingEnabled", SimpleType.BOOLEAN),
        SAMPLING_RATIO("samplingRatio", SimpleType.DOUBLE),
        SAMPLING_ROUTE_RATIOS("samplingRouteRatios", () -> ROUTES),
        TRACE_CONTROL_HEADERS_ENABLED("traceControlHeadersEnabled", SimpleType.BOOLEAN),
        VALIDATION_ENABLED("validationEnabled", SimpleType.BOOLEAN),
        VALIDATION_STRICT("validationStrict", SimpleType.BOOLEAN),
        APPLY_TRIGGER("applyTrigger", SimpleType.STRING),
        ATTEMPT_ID("attemptId", SimpleType.STRING),
        CANDIDATE_DIGEST("candidateDigest", SimpleType.STRING),
        EXPECTED_ACCEPTED_SEQUENCE("expectedAcceptedSequence", SimpleType.LONG),
        EXPECTED_RUNTIME_INSTANCE_ID("expectedRuntimeInstanceId", SimpleType.STRING),
        PROTOCOL_VERSION("protocolVersion", SimpleType.INTEGER),
        RUNTIME_POLICY("runtimePolicy", () -> POLICY),
        SOURCE_AUTHORITY("sourceAuthority", SimpleType.STRING),
        SOURCE_REVISION("sourceRevision", SimpleType.STRING),
        ACCEPTED_SEQUENCE("acceptedSequence", SimpleType.LONG),
        MESSAGE_CODE("messageCode", SimpleType.STRING),
        OUTCOME("outcome", SimpleType.STRING),
        PAYLOAD_DIGEST("payloadDigest", SimpleType.STRING),
        RESTART_REQUIRED("restartRequired", SimpleType.BOOLEAN),
        RESTART_REQUIRED_KEYS("restartRequiredKeys", PrivateControlWireTypes::arrayOfStrings),
        RUNTIME_INSTANCE_ID("runtimeInstanceId", SimpleType.STRING),
        VIOLATION_CODES("violationCodes", PrivateControlWireTypes::arrayOfStrings);

        private final String wireName;
        private final Supplier<OpenType<?>> type;

        WireField(String wireName, OpenType<?> type) {
            this(wireName, () -> type);
        }

        WireField(String wireName, Supplier<OpenType<?>> type) {
            this.wireName = wireName;
            this.type = type;
        }

        String wireName() {
            return wireName;
        }

        OpenType<?> type() {
            return type.get();
        }
    }

    static final CompositeType ROUTE_ROW = composite(
            "space.br1440.platform.tracing.control.RouteRatio.v1",
            WireField.ROUTE_RATIO, WireField.ROUTE_PREFIX
    );

    static final TabularType ROUTES = table(
            "space.br1440.platform.tracing.control.RouteRatios.v1",
            ROUTE_ROW,
            WireField.ROUTE_PREFIX
    );

    static final CompositeType POLICY = composite(
            "space.br1440.platform.tracing.control.RuntimePolicy.v1",
            WireField.DIAGNOSTICS_LOG_LEVEL,
            WireField.EXPORTER_ENABLED,
            WireField.SAMPLING_ENABLED,
            WireField.SAMPLING_RATIO,
            WireField.SAMPLING_ROUTE_RATIOS,
            WireField.TRACE_CONTROL_HEADERS_ENABLED,
            WireField.VALIDATION_ENABLED,
            WireField.VALIDATION_STRICT
    );

    static final CompositeType REQUEST = composite(
            "space.br1440.platform.tracing.control.ApplySnapshotRequest.v1",
            WireField.APPLY_TRIGGER,
            WireField.ATTEMPT_ID,
            WireField.CANDIDATE_DIGEST,
            WireField.EXPECTED_ACCEPTED_SEQUENCE,
            WireField.EXPECTED_RUNTIME_INSTANCE_ID,
            WireField.PROTOCOL_VERSION,
            WireField.RUNTIME_POLICY,
            WireField.SOURCE_AUTHORITY,
            WireField.SOURCE_REVISION
    );

    static final CompositeType RESPONSE = composite(
            "space.br1440.platform.tracing.control.ApplySnapshotResponse.v1",
            WireField.ACCEPTED_SEQUENCE,
            WireField.ATTEMPT_ID,
            WireField.MESSAGE_CODE,
            WireField.OUTCOME,
            WireField.PAYLOAD_DIGEST,
            WireField.PROTOCOL_VERSION,
            WireField.RESTART_REQUIRED,
            WireField.RESTART_REQUIRED_KEYS,
            WireField.RUNTIME_INSTANCE_ID,
            WireField.VIOLATION_CODES
    );

    static final String ACCEPTED_COMPLETE_TYPE_NAME = "AcceptedCompleteSnapshot/v1";

    static final Set<String> VALID_LOG_LEVELS = Set.of("OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE");

    static final int MAX_ROUTE_ENTRIES = 100;
    static final int MAX_ROUTE_PREFIX_LENGTH = 255;
    static final int MAX_LOGICAL_ACCEPTED_COMPLETE_BYTES = 32_768;

    static final CompositeType COMPLETE_RESPONSE = composite(
            ACCEPTED_COMPLETE_TYPE_NAME,
            WireField.ACCEPTED_SEQUENCE,
            WireField.RUNTIME_INSTANCE_ID,
            WireField.SAMPLING_ENABLED,
            WireField.SAMPLING_RATIO,
            WireField.SAMPLING_ROUTE_RATIOS,
            WireField.VALIDATION_ENABLED,
            WireField.VALIDATION_STRICT,
            WireField.EXPORTER_ENABLED,
            WireField.TRACE_CONTROL_HEADERS_ENABLED,
            WireField.DIAGNOSTICS_LOG_LEVEL
    );

    private static CompositeType composite(String name, WireField... fields) {
        String[] names = new String[fields.length];
        OpenType<?>[] types = new OpenType<?>[fields.length];
        for (int index = 0; index < fields.length; index++) {
            names[index] = fields[index].wireName();
            types[index] = fields[index].type();
        }

        try {
            return new CompositeType(name, name, names, names, types);
        } catch (OpenDataException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static TabularType table(String name, CompositeType row, WireField index) {
        try {
            return new TabularType(name, name, row, new String[]{index.wireName()});
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
