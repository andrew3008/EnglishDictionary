package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import space.br1440.platform.tracing.autoconfigure.control.configserver.ConfigServerSettingsManifestBuilder;
import space.br1440.platform.tracing.autoconfigure.control.configserver.ConfigServerSettingsProvenanceClassifier;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsManifestException;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

class MalformedResponseAndManifestPayloadTest {

    // -------------------------------------------------------------------------
    // Константы независимого стенда с некорректной нагрузкой
    // -------------------------------------------------------------------------

    private static final int MAX_ROUTE_ENTRIES = JmxWireContract.MAX_ROUTE_ENTRIES;
    private static final int MAX_ROUTE_PREFIX_LEN = JmxWireContract.MAX_ROUTE_PREFIX_UTF8_BYTES;
    private static final int MAX_PAYLOAD_BYTES = JmxWireContract.MAX_LOGICAL_BYTES;
    private static final String AGENT_INSTANCE_ID = "310ad563-bbe5-49cb-a72e-f3bb82bc32f2";

    // Подтипы коэффициентов маршрутов, повторяющие статическое состояние кодека
    private static final CompositeType ROUTE_ROW = routeRowType();
    private static final TabularType ROUTES_TYPE = routesType(ROUTE_ROW);

    // Канонический CompositeType принятой политики
    private static final CompositeType ACCEPTED_COMPLETE = acceptedCompleteType();

    // Намеренно ошибочный CompositeType для проверки несовпадения схемы
    private static final CompositeType WRONG_TYPE = wrongCompositeType();

    // Канонический CompositeType ответа applySnapshot
    private static final String[] RESPONSE_KEYS = {
            "acceptedSequence", "attemptId", "messageCode", "outcome", "payloadDigest",
            "agentInstanceId", "violationCodes"
    };
    private static final CompositeType RESPONSE_TYPE = responseType();

    // =========================================================================
    // Раздел 1 — пустой или ошибочный Java-тип исходного аргумента
    // =========================================================================

    @Test
    void decode_complete_null_raw_throws() {
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(null))
                .as("null raw MUST throw IllegalArgumentException (fail-closed)")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decode_complete_string_raw_throws() {
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode("not-composite"))
                .as("String raw MUST throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decode_complete_integer_raw_throws() {
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(42))
                .as("Integer raw MUST throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decode_complete_wrong_composite_type_throws() throws OpenDataException {
        CompositeData wrong = new CompositeDataSupport(
                WRONG_TYPE,
                new String[]{"dummy"},
                new Object[]{"value"}
        );

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(wrong))
                .as("wrong CompositeType MUST throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unexpected CompositeType");
    }

    // =========================================================================
    // Раздел 2 — пустые или отсутствующие обязательные поля
    // =========================================================================

    @Test
    void decode_complete_null_accepted_sequence_throws() throws OpenDataException {
        CompositeData cd = buildCompleteWith(Map.of(
                "agentInstanceId", "310ad563-bbe5-49cb-a72e-f3bb82bc32f2",
                "samplingEnabled", true,
                "samplingRatio", 1.0,
                "samplingRouteRatios", emptyRoutes(),
                "validationLevel", "WARNING",
                "exporterEnabled", true,
                "traceControlHeadersEnabled", true,
                "diagnosticsLogLevel", "INFO"
        ), /* nullFields= */ new String[]{"acceptedSequence"});

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .as("null acceptedSequence field MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acceptedSequence");
    }

    @Test
    void decode_complete_null_agent_instance_id_throws() throws OpenDataException {
        CompositeData cd = buildCompleteWith(Map.of(
                "acceptedSequence", 1L,
                "samplingEnabled", true,
                "samplingRatio", 1.0,
                "samplingRouteRatios", emptyRoutes(),
                "validationLevel", "WARNING",
                "exporterEnabled", true,
                "traceControlHeadersEnabled", true,
                "diagnosticsLogLevel", "INFO"
        ), /* nullFields= */ new String[]{"agentInstanceId"});

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .as("null agentInstanceId field MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentInstanceId");
    }

    @Test
    void decode_complete_null_diagnostics_log_level_throws() throws OpenDataException {
        CompositeData cd = buildCompleteWith(Map.of(
                "acceptedSequence", 1L,
                "agentInstanceId", "310ad563-bbe5-49cb-a72e-f3bb82bc32f2",
                "samplingEnabled", true,
                "samplingRatio", 1.0,
                "samplingRouteRatios", emptyRoutes(),
                "validationLevel", "WARNING",
                "exporterEnabled", true,
                "traceControlHeadersEnabled", true
        ), /* nullFields= */ new String[]{"diagnosticsLogLevel"});

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .as("null diagnosticsLogLevel field MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("diagnosticsLogLevel");
    }

    @Test
    void decode_complete_null_sampling_enabled_throws() throws OpenDataException {
        CompositeData cd = buildCompleteWith(Map.of(
                "acceptedSequence", 1L,
                "agentInstanceId", "310ad563-bbe5-49cb-a72e-f3bb82bc32f2",
                "samplingRatio", 1.0,
                "samplingRouteRatios", emptyRoutes(),
                "validationLevel", "WARNING",
                "exporterEnabled", true,
                "traceControlHeadersEnabled", true,
                "diagnosticsLogLevel", "INFO"
        ), /* nullFields= */ new String[]{"samplingEnabled"});

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .as("null samplingEnabled field MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("samplingEnabled");
    }

    // =========================================================================
    // Раздел 3 — недопустимые скалярные значения
    // =========================================================================

    @Test
    void decode_complete_negative_sequence_throws() {
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(
                validCompleteCd(-1L, AGENT_INSTANCE_ID, 1.0, emptyRoutes(), "INFO")))
                .as("negative acceptedSequence MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acceptedSequence");
    }

    @Test
    void decode_complete_blank_agent_instance_id_empty_throws() {
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(
                validCompleteCd(1L, "", 1.0, emptyRoutes(), "INFO")))
                .as("blank (empty) agentInstanceId MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentInstanceId");
    }

    @Test
    void decode_complete_blank_agent_instance_id_whitespace_throws() {
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(
                validCompleteCd(1L, "   ", 1.0, emptyRoutes(), "INFO")))
                .as("whitespace-only agentInstanceId MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentInstanceId");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.001, 1.001, -1.0, 2.0, -100.0, 100.0})
    void decode_complete_out_of_range_sampling_ratio_throws(double ratio) {
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(
                validCompleteCd(1L, AGENT_INSTANCE_ID, ratio, emptyRoutes(), "INFO")))
                .as("out-of-range samplingRatio " + ratio + " MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("samplingRatio");
    }

    @Test
    void decode_complete_nan_sampling_ratio_throws() {
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(
                validCompleteCd(1L, AGENT_INSTANCE_ID, Double.NaN, emptyRoutes(), "INFO")))
                .as("NaN samplingRatio MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("samplingRatio");
    }

    @Test
    void decode_complete_infinite_sampling_ratio_throws() {
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(
                validCompleteCd(1L, AGENT_INSTANCE_ID, Double.POSITIVE_INFINITY, emptyRoutes(), "INFO")))
                .as("+Infinity samplingRatio MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("samplingRatio");
    }

    @ParameterizedTest
    @ValueSource(strings = {"VERBOSE", "ALL", "FATAL", "FINEST", "verbose", "info", "debug", "1", "null"})
    void decode_complete_unknown_log_level_throws(String level) {
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(
                validCompleteCd(1L, AGENT_INSTANCE_ID, 1.0, emptyRoutes(), level)))
                .as("unknown log level '" + level + "' MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("diagnosticsLogLevel");
    }

    @Test
    void decode_complete_empty_log_level_throws() {
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(
                validCompleteCd(1L, AGENT_INSTANCE_ID, 1.0, emptyRoutes(), "")))
                .as("empty diagnosticsLogLevel MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("diagnosticsLogLevel");
    }

    // =========================================================================
    // Раздел 4 — нарушения коэффициентов маршрутов
    // =========================================================================

    @Test
    void decode_complete_empty_route_prefix_throws() throws OpenDataException {
        TabularData routes = routesWith("", 0.5);
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(
                validCompleteCd(1L, AGENT_INSTANCE_ID, 1.0, routes, "INFO")))
                .as("empty route prefix MUST throw")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decode_complete_blank_route_prefix_throws() throws OpenDataException {
        TabularData routes = routesWith("   ", 0.5);
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(
                validCompleteCd(1L, AGENT_INSTANCE_ID, 1.0, routes, "INFO")))
                .as("blank route prefix MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void decode_complete_iso_control_route_prefix_throws() throws OpenDataException {
        TabularData routes = routesWith("/api\u0001", 0.5);
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(
                validCompleteCd(1L, AGENT_INSTANCE_ID, 1.0, routes, "INFO")))
                .as("ISO-control route prefix MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO control")
                .hasMessageNotContaining("/api");
    }

    @Test
    void decode_complete_oversized_route_prefix_throws() throws OpenDataException {
        String longPrefix = "x".repeat(MAX_ROUTE_PREFIX_LEN + 1);
        TabularData routes = routesWith(longPrefix, 0.5);
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(
                validCompleteCd(1L, AGENT_INSTANCE_ID, 1.0, routes, "INFO")))
                .as("prefix length > 255 MUST throw")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decode_complete_route_ratio_below_zero_throws() throws OpenDataException {
        TabularData routes = routesWith("/api", -0.1);
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(
                validCompleteCd(1L, AGENT_INSTANCE_ID, 1.0, routes, "INFO")))
                .as("route ratio < 0.0 MUST throw")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decode_complete_route_ratio_above_one_throws() throws OpenDataException {
        TabularData routes = routesWith("/api", 1.1);
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(
                validCompleteCd(1L, AGENT_INSTANCE_ID, 1.0, routes, "INFO")))
                .as("route ratio > 1.0 MUST throw")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decode_complete_route_ratio_nan_throws() throws OpenDataException {
        TabularData routes = routesWith("/api", Double.NaN);
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(
                validCompleteCd(1L, AGENT_INSTANCE_ID, 1.0, routes, "INFO")))
                .as("NaN route ratio MUST throw")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decode_complete_too_many_route_entries_throws() throws OpenDataException {
        TabularData routes = new TabularDataSupport(ROUTES_TYPE);
        for (int i = 0; i <= MAX_ROUTE_ENTRIES; i++) {
            routes.put(new CompositeDataSupport(
                    ROUTE_ROW,
                    new String[]{"ratio", "routePrefix"},
                    new Object[]{0.5, "/route" + i}));
        }

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(
                validCompleteCd(1L, AGENT_INSTANCE_ID, 1.0, routes, "INFO")))
                .as("more than " + MAX_ROUTE_ENTRIES + " route entries MUST throw")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // Раздел 5 — превышение логического размера нагрузки
    // =========================================================================

    @Test
    void decode_complete_oversized_agent_instance_id_throws() {
        String oversizedId = "x".repeat(MAX_PAYLOAD_BYTES + 1000);
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(
                validCompleteCd(1L, oversizedId, 1.0, emptyRoutes(), "INFO")))
                .as("agentInstanceId exceeding payload bound MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("logical payload");
    }

    @Test
    void decode_complete_oversized_routes_payload_throws() throws OpenDataException {
        TabularData routes = new TabularDataSupport(ROUTES_TYPE);
        for (int i = 0; i < MAX_ROUTE_ENTRIES; i++) {
            String prefix = "/r" + i + "-" + "a".repeat(200);
            routes.put(new CompositeDataSupport(
                    ROUTE_ROW,
                    new String[]{"ratio", "routePrefix"},
                    new Object[]{0.5, prefix}));
        }

        String bigId = "r".repeat(16_000);
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(
                validCompleteCd(1L, bigId, 1.0, routes, "INFO")))
                .as("routes + agentInstanceId exceeding payload bound MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("logical payload");
    }

    // =========================================================================
    // Раздел 6 — пустой или ошибочный CompositeType ответа
    // =========================================================================

    @Test
    void decode_response_null_throws() {
        assertThatThrownBy(() -> PlatformJmxResponse.decode(null))
                .as("null response CompositeData MUST throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CompositeData");
    }

    @Test
    void decode_response_wrong_type_throws() throws OpenDataException {
        CompositeData wrong = new CompositeDataSupport(
                WRONG_TYPE,
                new String[]{"dummy"},
                new Object[]{"value"});

        assertThatThrownBy(() -> PlatformJmxResponse.decode(wrong))
                .as("wrong CompositeType response MUST throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CompositeType");
    }

    @Test
    void decode_response_null_outcome_throws() throws OpenDataException {
        CompositeData response = buildResponseWith(Map.of(), new String[]{"outcome"});
        assertThatThrownBy(() -> PlatformJmxResponse.decode(response))
                .as("null outcome field MUST throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcome");
    }

    @Test
    void decode_response_blank_outcome_throws() throws OpenDataException {
        CompositeData response = buildResponseWith(Map.of("outcome", "   "), new String[0]);
        assertThatThrownBy(() -> PlatformJmxResponse.decode(response))
                .as("blank outcome field MUST throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcome");
    }

    // =========================================================================
    // Раздел 7 — некорректное окружение для ConfigServerSettingsManifestBuilder
    // =========================================================================

    @Test
    void builder_non_numeric_route_ratio_throws() {
        MockEnvironment env = fullPolicyEnvironment();
        putAuthoritativeRouteRatio(env, "/api", "not-a-number");

        ConfigServerSettingsManifestBuilder builder =
                new ConfigServerSettingsManifestBuilder(env, configServerClassifier(env));

        assertThatThrownBy(builder::build)
                .as("non-numeric route-ratio value MUST throw TracingSettingsManifestException")
                .isInstanceOf(TracingSettingsManifestException.class);
    }

    @Test
    void builder_route_ratio_above_one_throws() {
        MockEnvironment env = fullPolicyEnvironment();
        putAuthoritativeRouteRatio(env, "/api", "1.5");

        ConfigServerSettingsManifestBuilder builder =
                new ConfigServerSettingsManifestBuilder(env, configServerClassifier(env));

        assertThatThrownBy(builder::build)
                .as("route-ratio > 1.0 MUST throw IllegalArgumentException (value invariant)")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("/api");
    }

    @Test
    void builder_route_ratio_below_zero_throws() {
        MockEnvironment env = fullPolicyEnvironment();
        putAuthoritativeRouteRatio(env, "/api", "-0.1");

        ConfigServerSettingsManifestBuilder builder =
                new ConfigServerSettingsManifestBuilder(env, configServerClassifier(env));

        assertThatThrownBy(builder::build)
                .as("route-ratio < 0.0 MUST throw IllegalArgumentException (value invariant)")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("/api");
    }

    // =========================================================================
    // Вспомогательные конструкторы корректных CompositeData
    // =========================================================================

    private CompositeData validCompleteCd(
            long seq, String instanceId, double ratio,
            TabularData routes, String logLevel) {
        try {
            return new CompositeDataSupport(
                    ACCEPTED_COMPLETE,
                    new String[]{
                            "acceptedSequence", "agentInstanceId",
                            "acceptedPolicyDigest", "lastApplyOutcome", "lastAttemptId", "failureCode",
                            "samplingEnabled", "samplingRatio", "samplingRouteRatios",
                            "validationLevel", "exporterEnabled",
                            "traceControlHeadersEnabled", "diagnosticsLogLevel"
                    },
                    new Object[]{
                            seq, instanceId,
                            "accepted-policy-digest", "APPLIED", "attempt-id", "",
                            true, ratio, routes,
                            "WARNING", true,
                            true, logLevel
                    });
        } catch (OpenDataException e) {
            throw new AssertionError("Test helper failed to build CompositeData", e);
        }
    }

    private CompositeData buildCompleteWith(Map<String, Object> nonNullValues,
                                            String[] nullFields) throws OpenDataException {
        String[] keys = {
                "acceptedSequence", "agentInstanceId",
                "acceptedPolicyDigest", "lastApplyOutcome", "lastAttemptId", "failureCode",
                "samplingEnabled", "samplingRatio", "samplingRouteRatios",
                "validationLevel", "exporterEnabled",
                "traceControlHeadersEnabled", "diagnosticsLogLevel"
        };

        Object[] values = new Object[keys.length];
        java.util.Set<String> nullSet = java.util.Set.of(nullFields);
        for (int i = 0; i < keys.length; i++) {
            if (nullSet.contains(keys[i])) {
                values[i] = null;
            } else {
                values[i] = nonNullValues.getOrDefault(keys[i], defaultFor(keys[i]));
            }
        }
        return new CompositeDataSupport(ACCEPTED_COMPLETE, keys, values);
    }

    private static Object defaultFor(String key) {
        return switch (key) {
            case "acceptedSequence" -> 1L;
            case "agentInstanceId" -> "310ad563-bbe5-49cb-a72e-f3bb82bc32f2";
            case "acceptedPolicyDigest" -> "accepted-policy-digest";
            case "lastApplyOutcome" -> "APPLIED";
            case "lastAttemptId" -> "attempt-id";
            case "failureCode" -> "";
            case "samplingEnabled",
                 "exporterEnabled",
                 "traceControlHeadersEnabled" -> true;
            case "validationLevel" -> "WARNING";
            case "samplingRatio" -> 1.0;
            case "samplingRouteRatios" -> emptyRoutes();
            case "diagnosticsLogLevel" -> "INFO";
            default -> throw new IllegalArgumentException("Unknown key: " + key);
        };
    }

    // =========================================================================
    // Вспомогательная сборка TabularData
    // =========================================================================

    private static TabularData emptyRoutes() {
        return new TabularDataSupport(ROUTES_TYPE);
    }

    private static TabularData routesWith(String prefix, double ratio) throws OpenDataException {
        TabularDataSupport routes = new TabularDataSupport(ROUTES_TYPE);
        routes.put(new CompositeDataSupport(
                ROUTE_ROW,
                new String[]{"ratio", "routePrefix"},
                new Object[]{ratio, prefix}));
        return routes;
    }

    // =========================================================================
    // Вспомогательные конструкторы Open-Type
    // =========================================================================

    private static CompositeType routeRowType() {
        try {
            return new CompositeType(
                    "space.br1440.platform.tracing.control.RouteRatio.v1",
                    "space.br1440.platform.tracing.control.RouteRatio.v1",
                    new String[]{"ratio", "routePrefix"},
                    new String[]{"ratio", "routePrefix"},
                    new OpenType<?>[]{SimpleType.DOUBLE, SimpleType.STRING});
        } catch (OpenDataException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static TabularType routesType(CompositeType rowType) {
        try {
            return new TabularType(
                    "space.br1440.platform.tracing.control.RouteRatios.v1",
                    "space.br1440.platform.tracing.control.RouteRatios.v1",
                    rowType,
                    new String[]{"routePrefix"});
        } catch (OpenDataException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static CompositeType acceptedCompleteType() {
        try {
            return new CompositeType(
                    "AcceptedCompleteSnapshot/v1",
                    "AcceptedCompleteSnapshot/v1",
                    new String[]{
                            "acceptedSequence", "agentInstanceId",
                            "acceptedPolicyDigest", "lastApplyOutcome", "lastAttemptId", "failureCode",
                            "samplingEnabled", "samplingRatio", "samplingRouteRatios",
                            "validationLevel", "exporterEnabled",
                            "traceControlHeadersEnabled", "diagnosticsLogLevel"
                    },
                    new String[]{
                            "acceptedSequence", "agentInstanceId",
                            "acceptedPolicyDigest", "lastApplyOutcome", "lastAttemptId", "failureCode",
                            "samplingEnabled", "samplingRatio", "samplingRouteRatios",
                            "validationLevel", "exporterEnabled",
                            "traceControlHeadersEnabled", "diagnosticsLogLevel"
                    },
                    new OpenType<?>[]{
                            SimpleType.LONG, SimpleType.STRING,
                            SimpleType.STRING, SimpleType.STRING, SimpleType.STRING, SimpleType.STRING,
                            SimpleType.BOOLEAN, SimpleType.DOUBLE, routesType(routeRowType()),
                            SimpleType.STRING, SimpleType.BOOLEAN,
                            SimpleType.BOOLEAN, SimpleType.STRING
                    });
        } catch (OpenDataException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static CompositeType wrongCompositeType() {
        try {
            return new CompositeType(
                    "WrongType/v999", "WrongType/v999",
                    new String[]{"dummy"}, new String[]{"dummy"},
                    new OpenType<?>[]{SimpleType.STRING});
        } catch (OpenDataException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static CompositeType responseType() {
        try {
            String[] keys = RESPONSE_KEYS;
            return new CompositeType(
                    "space.br1440.platform.tracing.control.ApplySnapshotResponse.v1",
                    "space.br1440.platform.tracing.control.ApplySnapshotResponse.v1",
                    keys, keys,
                    new OpenType<?>[]{
                            SimpleType.LONG, SimpleType.STRING, SimpleType.STRING, SimpleType.STRING,
                            SimpleType.STRING, SimpleType.STRING, arrayOfStrings()
                    });
        } catch (OpenDataException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static ArrayType<String> arrayOfStrings() {
        try {
            return new ArrayType<>(1, SimpleType.STRING);
        } catch (OpenDataException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Собирает {@code CompositeData} по сгенерированной JMX-схеме ответа.
     */
    private static CompositeData buildResponseWith(
            Map<String, Object> nonNullOverrides,
            String[] nullFields) throws OpenDataException {
        java.util.Set<String> nullSet = java.util.Set.of(nullFields);
        Object[] values = new Object[RESPONSE_KEYS.length];
        for (int i = 0; i < RESPONSE_KEYS.length; i++) {
            String key = RESPONSE_KEYS[i];
            if (nullSet.contains(key)) {
                values[i] = null;
            } else {
                values[i] = nonNullOverrides.getOrDefault(key, defaultForResponseField(key));
            }
        }
        return new CompositeDataSupport(RESPONSE_TYPE, RESPONSE_KEYS, values);
    }

    private static Object defaultForResponseField(String key) {
        return switch (key) {
            case "acceptedSequence" -> 1L;
            case "attemptId" -> "attempt-1";
            case "messageCode" -> "NONE";
            case "outcome" -> "APPLIED";
            case "payloadDigest" -> "a".repeat(64);
            case "agentInstanceId" -> "310ad563-bbe5-49cb-a72e-f3bb82bc32f2";
            case "violationCodes" -> new String[0];
            default -> throw new IllegalArgumentException("Unknown key: " + key);
        };
    }

    private static MockEnvironment fullPolicyEnvironment() {
        MockEnvironment env = new MockEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource(
                "configserver:test",
                Map.of(
                        "platform.tracing.policy.sampling.enabled", "true",
                        "platform.tracing.policy.sampling.ratio", "1.0",
                        "platform.tracing.policy.validation.level", "WARNING",
                        "platform.tracing.policy.exporter.enabled", "true",
                        "platform.tracing.policy.propagation.trace-control-headers.enabled", "true",
                        "platform.tracing.policy.diagnostics.log-level", "INFO"
                )));
        return env;
    }

    @SuppressWarnings("SameParameterValue")
    private static void putAuthoritativeRouteRatio(MockEnvironment env, String route, String value) {
        env.getPropertySources().addFirst(new MapPropertySource(
                "configserver:test-route-ratios",
                Map.of("platform.tracing.policy.sampling.route-ratios." + route, value))
        );
    }

    private static ConfigServerSettingsProvenanceClassifier configServerClassifier(MockEnvironment env) {
        return new ConfigServerSettingsProvenanceClassifier(env);
    }
}
