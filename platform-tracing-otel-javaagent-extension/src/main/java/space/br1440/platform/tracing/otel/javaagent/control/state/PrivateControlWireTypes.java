package space.br1440.platform.tracing.otel.javaagent.control.state;

import java.util.function.Supplier;

import javax.management.openmbean.ArrayType;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularType;

/**
 * Exact Open-Type schema версии 1 на Agent-side границе.
 * <p>
 * Независимая от Spring-side копия: этот classloader plane не делит Java-тип схемы с
 * App-side {@code PrivateControlWireTypes} — их равенство доказывается по значениям
 * OpenMBean ({@code ObjectNameConstantsConsistencyTest}, {@code PrivateControlWireSchemaGoldenTest}),
 * а не общим кодом.
 * <p>
 * {@link WireField} — единственный владелец пары «wire-имя + Open-Type» для каждого
 * уникального поля протокола; см. подробное обоснование Supplier-полей в Javadoc самого enum.
 */
final class PrivateControlWireTypes {

    /**
     * Единственное уникальное wire-поле протокола ровно один раз — имя и Open-Type неразрывны.
     * <p>
     * {@code Supplier<OpenType<?>>} используется только там, где тип поля зависит от другого
     * статического поля этого класса ({@link #ROUTES}, {@link #POLICY}) или от фабричного метода
     * ({@link #arrayOfStrings()}). Вычисление откладывается до вызова {@link #type()}: на момент
     * загрузки этого enum (первое обращение — построение {@link #ROUTE_ROW}) статические поля
     * {@code ROUTES}/{@code POLICY} внешнего класса ещё не инициализированы, поэтому эагерное
     * вычисление привело бы к {@code null} вместо реального типа. Порядок объявления полей класса
     * гарантирует, что supplier читается уже после того, как соответствующая внешняя схема
     * построена.
     */
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

    private static final WireField[] ROUTE_ROW_FIELDS = {WireField.ROUTE_RATIO, WireField.ROUTE_PREFIX};
    static final CompositeType ROUTE_ROW = composite(
            "space.br1440.platform.tracing.control.RouteRatio.v1",
            ROUTE_ROW_FIELDS);
    static final TabularType ROUTES = table(
            "space.br1440.platform.tracing.control.RouteRatios.v1",
            ROUTE_ROW,
            WireField.ROUTE_PREFIX);

    private static final WireField[] POLICY_FIELDS = {
            WireField.DIAGNOSTICS_LOG_LEVEL,
            WireField.EXPORTER_ENABLED,
            WireField.SAMPLING_ENABLED,
            WireField.SAMPLING_RATIO,
            WireField.SAMPLING_ROUTE_RATIOS,
            WireField.TRACE_CONTROL_HEADERS_ENABLED,
            WireField.VALIDATION_ENABLED,
            WireField.VALIDATION_STRICT
    };
    static final CompositeType POLICY = composite(
            "space.br1440.platform.tracing.control.RuntimePolicy.v1",
            POLICY_FIELDS);

    private static final WireField[] REQUEST_FIELDS = {
            WireField.APPLY_TRIGGER,
            WireField.ATTEMPT_ID,
            WireField.CANDIDATE_DIGEST,
            WireField.EXPECTED_ACCEPTED_SEQUENCE,
            WireField.EXPECTED_RUNTIME_INSTANCE_ID,
            WireField.PROTOCOL_VERSION,
            WireField.RUNTIME_POLICY,
            WireField.SOURCE_AUTHORITY,
            WireField.SOURCE_REVISION
    };
    static final CompositeType REQUEST = composite(
            "space.br1440.platform.tracing.control.ApplySnapshotRequest.v1",
            REQUEST_FIELDS);

    private static final WireField[] RESPONSE_FIELDS = {
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
    };
    static final CompositeType RESPONSE = composite(
            "space.br1440.platform.tracing.control.ApplySnapshotResponse.v1",
            RESPONSE_FIELDS);

    /**
     * Versioned CompositeType for readAcceptedComplete response (PRIV-R07).
     * Type name is exact and tested by Spring decoder.
     */
    private static final WireField[] ACCEPTED_COMPLETE_FIELDS = {
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
    };
    static final CompositeType ACCEPTED_COMPLETE = composite(
            "AcceptedCompleteSnapshot/v1",
            ACCEPTED_COMPLETE_FIELDS);

    private PrivateControlWireTypes() {
    }

    static void initialize() {
        if (REQUEST.keySet().size() != REQUEST_FIELDS.length
                || POLICY.keySet().size() != POLICY_FIELDS.length
                || ACCEPTED_COMPLETE.keySet().size() != ACCEPTED_COMPLETE_FIELDS.length) {
            throw new IllegalStateException("Private control Open-Type schema is inconsistent");
        }
    }

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
