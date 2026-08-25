package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsManifestException;
import space.br1440.platform.tracing.autoconfigure.control.configserver.ConfigServerSettingsManifestBuilder;
import space.br1440.platform.tracing.autoconfigure.control.configserver.ConfigServerSettingsProvenanceClassifier;

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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Supplemental fail-closed matrix for malformed / null / oversized Open-Type payloads
 * and malformed Config Server route-ratio Environment values.
 *
 * <p>Branch name {@code p5-5-malformed-payload-tests} maps to plan item
 * {@code p5-malformed-payload-tests} (§20 P5.4), not §20 P5.5 secret canary.
 * Complements {@link P54MalformedPayloadTest} with additional decodeResponse /
 * null-field / route-bound cases and {@link ConfigServerSettingsManifestBuilder} parse
 * fail-closed coverage.
 *
 * @see AcceptedCompleteSnapshotDecoder
 * @see PrivateControlResponse
 * @see ConfigServerSettingsManifestBuilder
 * @see P54MalformedPayloadTest
 */
class P55MalformedPayloadTest {

    // -------------------------------------------------------------------------
    // Shared wire-type constants (mirrors PrivateControlWireTypes internals)
    // -------------------------------------------------------------------------

    private static final int MAX_ROUTE_ENTRIES    = 100;
    private static final int MAX_ROUTE_PREFIX_LEN = 255;
    private static final int MAX_PAYLOAD_BYTES    = 32_768;

    // Route-ratio sub-types  (mirrors codec static state)
    private static final CompositeType ROUTE_ROW   = routeRowType();
    private static final TabularType   ROUTES_TYPE = routesType(ROUTE_ROW);

    // The canonical PRIV-R07 CompositeType
    private static final CompositeType ACCEPTED_COMPLETE = acceptedCompleteType();

    // A deliberately wrong CompositeType to test schema mismatch
    private static final CompositeType WRONG_TYPE = wrongCompositeType();

    // The canonical applySnapshot response CompositeType
    private static final String[] RESPONSE_KEYS = {
            "acceptedSequence", "attemptId", "messageCode", "outcome", "payloadDigest",
            "protocolVersion", "runtimeInstanceId", "violationCodes"
    };
    private static final CompositeType RESPONSE_TYPE = responseType();

    // =========================================================================
    // SECTION 1 — null / wrong Java type for raw argument
    // =========================================================================

    /** P5.5-1a: null raw object → fail-closed. */
    @Test
    void decode_complete_null_raw_throws() {
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(null))
                .as("null raw MUST throw IllegalArgumentException (fail-closed)")
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** P5.5-1b: raw = String → not CompositeData → fail-closed. */
    @Test
    void decode_complete_string_raw_throws() {
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode("not-composite"))
                .as("String raw MUST throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** P5.5-1c: raw = Integer → not CompositeData → fail-closed. */
    @Test
    void decode_complete_integer_raw_throws() {
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(42))
                .as("Integer raw MUST throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** P5.5-1d: CompositeData with wrong CompositeType name → schema mismatch → fail-closed. */
    @Test
    void decode_complete_wrong_composite_type_throws() throws OpenDataException {
        CompositeData wrong = new CompositeDataSupport(
                WRONG_TYPE,
                new String[]{"dummy"},
                new Object[]{"value"});
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(wrong))
                .as("wrong CompositeType MUST throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unexpected CompositeType");
    }

    // =========================================================================
    // SECTION 2 — null / missing required fields
    // =========================================================================

    /** acceptedSequence field is null. */
    @Test
    void decode_complete_null_accepted_sequence_throws() throws OpenDataException {
        // Map.of forbids null values — omit acceptedSequence; nullFields forces null.
        CompositeData cd = buildCompleteWith(Map.of(
                "runtimeInstanceId",          "inst-1",
                "samplingEnabled",            true,
                "samplingRatio",              1.0,
                "samplingRouteRatios",        emptyRoutes(),
                "validationEnabled",          true,
                "validationStrict",           false,
                "exporterEnabled",            true,
                "traceControlHeadersEnabled", true,
                "diagnosticsLogLevel",        "INFO"
        ), /* nullFields= */ new String[]{"acceptedSequence"});

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .as("null acceptedSequence field MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acceptedSequence");
    }

    /** P5.5-2b: runtimeInstanceId field is null. */
    @Test
    void decode_complete_null_runtime_instance_id_throws() throws OpenDataException {
        CompositeData cd = buildCompleteWith(Map.of(
                "acceptedSequence",           1L,
                "samplingEnabled",            true,
                "samplingRatio",              1.0,
                "samplingRouteRatios",        emptyRoutes(),
                "validationEnabled",          true,
                "validationStrict",           false,
                "exporterEnabled",            true,
                "traceControlHeadersEnabled", true,
                "diagnosticsLogLevel",        "INFO"
        ), /* nullFields= */ new String[]{"runtimeInstanceId"});

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .as("null runtimeInstanceId field MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtimeInstanceId");
    }

    /** P5.5-2c: diagnosticsLogLevel field is null. */
    @Test
    void decode_complete_null_diagnostics_log_level_throws() throws OpenDataException {
        CompositeData cd = buildCompleteWith(Map.of(
                "acceptedSequence",           1L,
                "runtimeInstanceId",          "inst-1",
                "samplingEnabled",            true,
                "samplingRatio",              1.0,
                "samplingRouteRatios",        emptyRoutes(),
                "validationEnabled",          true,
                "validationStrict",           false,
                "exporterEnabled",            true,
                "traceControlHeadersEnabled", true
        ), /* nullFields= */ new String[]{"diagnosticsLogLevel"});

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .as("null diagnosticsLogLevel field MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("diagnosticsLogLevel");
    }

    /** P5.5-2d: samplingEnabled field is null. */
    @Test
    void decode_complete_null_sampling_enabled_throws() throws OpenDataException {
        CompositeData cd = buildCompleteWith(Map.of(
                "acceptedSequence",           1L,
                "runtimeInstanceId",          "inst-1",
                "samplingRatio",              1.0,
                "samplingRouteRatios",        emptyRoutes(),
                "validationEnabled",          true,
                "validationStrict",           false,
                "exporterEnabled",            true,
                "traceControlHeadersEnabled", true,
                "diagnosticsLogLevel",        "INFO"
        ), /* nullFields= */ new String[]{"samplingEnabled"});

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .as("null samplingEnabled field MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("samplingEnabled");
    }

    // =========================================================================
    // SECTION 3 — invalid scalar values
    // =========================================================================

    /** P5.5-3a: acceptedSequence < 0. */
    @Test
    void decode_complete_negative_sequence_throws() {
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(
                validCompleteCd(-1L, "inst", 1.0, emptyRoutes(), "INFO")))
                .as("negative acceptedSequence MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acceptedSequence");
    }

    /** P5.5-3b: runtimeInstanceId = empty string. */
    @Test
    void decode_complete_blank_runtime_instance_id_empty_throws() {
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(
                validCompleteCd(1L, "", 1.0, emptyRoutes(), "INFO")))
                .as("blank (empty) runtimeInstanceId MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtimeInstanceId");
    }

    /** P5.5-3c: runtimeInstanceId = whitespace-only string. */
    @Test
    void decode_complete_blank_runtime_instance_id_whitespace_throws() {
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(
                validCompleteCd(1L, "   ", 1.0, emptyRoutes(), "INFO")))
                .as("whitespace-only runtimeInstanceId MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtimeInstanceId");
    }

    /** P5.5-3d/e: samplingRatio out of [0.0, 1.0]. */
    @ParameterizedTest
    @ValueSource(doubles = {-0.001, 1.001, -1.0, 2.0, -100.0, 100.0})
    void decode_complete_out_of_range_sampling_ratio_throws(double ratio) {
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(
                validCompleteCd(1L, "inst", ratio, emptyRoutes(), "INFO")))
                .as("out-of-range samplingRatio " + ratio + " MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("samplingRatio");
    }

    /** P5.5-3f: samplingRatio = NaN. */
    @Test
    void decode_complete_nan_sampling_ratio_throws() {
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(
                validCompleteCd(1L, "inst", Double.NaN, emptyRoutes(), "INFO")))
                .as("NaN samplingRatio MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("samplingRatio");
    }

    /** P5.5-3g: samplingRatio = +Infinity. */
    @Test
    void decode_complete_infinite_sampling_ratio_throws() {
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(
                validCompleteCd(1L, "inst", Double.POSITIVE_INFINITY, emptyRoutes(), "INFO")))
                .as("+Infinity samplingRatio MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("samplingRatio");
    }

    /** P5.5-3h: diagnosticsLogLevel = unknown value. */
    @ParameterizedTest
    @ValueSource(strings = {"VERBOSE", "ALL", "FATAL", "FINEST", "verbose", "info", "debug", "1", "null"})
    void decode_complete_unknown_log_level_throws(String level) {
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(
                validCompleteCd(1L, "inst", 1.0, emptyRoutes(), level)))
                .as("unknown log level '" + level + "' MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("diagnosticsLogLevel");
    }

    /** P5.5-3i: diagnosticsLogLevel = empty string. */
    @Test
    void decode_complete_empty_log_level_throws() {
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(
                validCompleteCd(1L, "inst", 1.0, emptyRoutes(), "")))
                .as("empty diagnosticsLogLevel MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("diagnosticsLogLevel");
    }

    // =========================================================================
    // SECTION 4 — route-ratio violations
    // =========================================================================

    /** P5.5-4a: route prefix = empty string. */
    @Test
    void decode_complete_empty_route_prefix_throws() throws OpenDataException {
        TabularData routes = routesWith("", 0.5);
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(
                validCompleteCd(1L, "inst", 1.0, routes, "INFO")))
                .as("empty route prefix MUST throw")
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Slice C: blank (whitespace-only) prefix — паритет с Agent isBlank(). */
    @Test
    void decode_complete_blank_route_prefix_throws() throws OpenDataException {
        TabularData routes = routesWith("   ", 0.5);
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(
                validCompleteCd(1L, "inst", 1.0, routes, "INFO")))
                .as("blank route prefix MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    /** Slice C: ISO-control в prefix — паритет с Agent isISOControl(). */
    @Test
    void decode_complete_iso_control_route_prefix_throws() throws OpenDataException {
        TabularData routes = routesWith("/api\u0001", 0.5);
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(
                validCompleteCd(1L, "inst", 1.0, routes, "INFO")))
                .as("ISO-control route prefix MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO control")
                .hasMessageNotContaining("/api");
    }

    /** P5.5-4b: route prefix length > MAX_ROUTE_PREFIX_LENGTH (255 UTF-16 code units). */
    @Test
    void decode_complete_oversized_route_prefix_throws() throws OpenDataException {
        String longPrefix = "x".repeat(MAX_ROUTE_PREFIX_LEN + 1);
        TabularData routes = routesWith(longPrefix, 0.5);
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(
                validCompleteCd(1L, "inst", 1.0, routes, "INFO")))
                .as("prefix length > 255 MUST throw")
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** P5.5-4c: route ratio < 0.0. */
    @Test
    void decode_complete_route_ratio_below_zero_throws() throws OpenDataException {
        TabularData routes = routesWith("/api", -0.1);
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(
                validCompleteCd(1L, "inst", 1.0, routes, "INFO")))
                .as("route ratio < 0.0 MUST throw")
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** P5.5-4d: route ratio > 1.0. */
    @Test
    void decode_complete_route_ratio_above_one_throws() throws OpenDataException {
        TabularData routes = routesWith("/api", 1.1);
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(
                validCompleteCd(1L, "inst", 1.0, routes, "INFO")))
                .as("route ratio > 1.0 MUST throw")
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** P5.5-4e: route ratio = NaN. */
    @Test
    void decode_complete_route_ratio_nan_throws() throws OpenDataException {
        TabularData routes = routesWith("/api", Double.NaN);
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(
                validCompleteCd(1L, "inst", 1.0, routes, "INFO")))
                .as("NaN route ratio MUST throw")
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** P5.5-4f: too many route entries (MAX_ROUTE_ENTRIES + 1 = 101). */
    @Test
    void decode_complete_too_many_route_entries_throws() throws OpenDataException {
        TabularData routes = new TabularDataSupport(ROUTES_TYPE);
        for (int i = 0; i <= MAX_ROUTE_ENTRIES; i++) {
            routes.put(new CompositeDataSupport(
                    ROUTE_ROW,
                    new String[]{"ratio", "routePrefix"},
                    new Object[]{0.5, "/route" + i}));
        }
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(
                validCompleteCd(1L, "inst", 1.0, routes, "INFO")))
                .as("more than " + MAX_ROUTE_ENTRIES + " route entries MUST throw")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // SECTION 5 — oversized logical payload
    // =========================================================================

    /**
     * P5.5-5a: runtimeInstanceId alone exceeds MAX_LOGICAL_ACCEPTED_COMPLETE_BYTES.
     * <p>
     * A 33 000-byte runtimeInstanceId would be pathological in any real deployment;
     * the codec MUST reject it rather than quietly forwarding unbounded strings.
     */
    @Test
    void decode_complete_oversized_runtime_instance_id_throws() {
        String oversizedId = "x".repeat(MAX_PAYLOAD_BYTES + 1000);
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(
                validCompleteCd(1L, oversizedId, 1.0, emptyRoutes(), "INFO")))
                .as("runtimeInstanceId exceeding payload bound MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("logical payload");
    }

    /**
     * P5.5-5b: routes collectively exceed MAX_LOGICAL_ACCEPTED_COMPLETE_BYTES.
     * <p>
     * Each route contributes {@code prefixLength + 16} bytes to the logical size.
     * 300 routes of prefix length 120 = 300 * (120 + 16) = 40 800 bytes, which
     * exceeds the 32 768-byte bound.
     */
    @Test
    void decode_complete_oversized_routes_payload_throws() throws OpenDataException {
        // Stay at MAX_ROUTE_ENTRIES (100) so entry-count check does not fire first;
        // push logical size over 32768 via long prefixes + large runtimeInstanceId.
        TabularData routes = new TabularDataSupport(ROUTES_TYPE);
        for (int i = 0; i < MAX_ROUTE_ENTRIES; i++) {
            String prefix = "/r" + i + "-" + "a".repeat(200);
            routes.put(new CompositeDataSupport(
                    ROUTE_ROW,
                    new String[]{"ratio", "routePrefix"},
                    new Object[]{0.5, prefix}));
        }
        // 100 * (≈205 + 16) ≈ 22_100 + 16_000 id + overhead > 32_768
        String bigId = "r".repeat(16_000);
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(
                validCompleteCd(1L, bigId, 1.0, routes, "INFO")))
                .as("routes + runtimeInstanceId exceeding payload bound MUST throw")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("logical payload");
    }

    // =========================================================================
    // SECTION 6 — decodeResponse — null / wrong CompositeType
    // =========================================================================

    /** P5.5-6a: null CompositeData to PrivateControlResponse.decode. */
    @Test
    void decode_response_null_throws() {
        assertThatThrownBy(() -> PrivateControlResponse.decode(null))
                .as("null response CompositeData MUST throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CompositeData");
    }

    /** P5.5-6b: CompositeData with wrong CompositeType name to PrivateControlResponse.decode. */
    @Test
    void decode_response_wrong_type_throws() throws OpenDataException {
        CompositeData wrong = new CompositeDataSupport(
                WRONG_TYPE,
                new String[]{"dummy"},
                new Object[]{"value"});
        assertThatThrownBy(() -> PrivateControlResponse.decode(wrong))
                .as("wrong CompositeType response MUST throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CompositeType");
    }

    /**
     * P5.5-6c: {@code outcome} field is {@code null} (Commit 4 of plan {@code wire-field-descriptors}
     * — fail-open regression guard). Before this fix, {@code PrivateControlResponse.decode} used an
     * unchecked {@code (String) response.get("outcome")} cast, so a {@code null} value silently
     * produced {@code outcome() == null} instead of failing here at the decode boundary.
     */
    @Test
    void decode_response_null_outcome_throws() throws OpenDataException {
        CompositeData response = buildResponseWith(Map.of(), new String[]{"outcome"});
        assertThatThrownBy(() -> PrivateControlResponse.decode(response))
                .as("null outcome field MUST throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcome");
    }

    /** P5.5-6d: {@code outcome} field is blank. */
    @Test
    void decode_response_blank_outcome_throws() throws OpenDataException {
        CompositeData response = buildResponseWith(Map.of("outcome", "   "), new String[0]);
        assertThatThrownBy(() -> PrivateControlResponse.decode(response))
                .as("blank outcome field MUST throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcome");
    }

    // =========================================================================
    // SECTION 7 — ConfigServerSettingsManifestBuilder — malformed Environment
    // =========================================================================

    /**
     * P5.5-7a: route-ratio value is non-numeric ("not-a-number").
     * <p>
     * Plan §12: fail-closed manifest builder; any parse error MUST throw
     * {@link TracingSettingsManifestException}.
     */
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

    /**
     * P5.5-7b: route-ratio value > 1.0 — value invariant принадлежит snapshot
     * ({@link IllegalArgumentException}), а не manifest completeness/parse.
     */
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

    /**
     * P5.5-7c: route-ratio value < 0.0 — value invariant принадлежит snapshot
     * ({@link IllegalArgumentException}), а не manifest completeness/parse.
     */
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
    // Helper — valid CompositeData constructors
    // =========================================================================

    private CompositeData validCompleteCd(
            long seq, String instanceId, double ratio,
            TabularData routes, String logLevel) {
        try {
            return new CompositeDataSupport(
                    ACCEPTED_COMPLETE,
                    new String[]{
                            "acceptedSequence", "runtimeInstanceId",
                            "samplingEnabled", "samplingRatio", "samplingRouteRatios",
                            "validationEnabled", "validationStrict", "exporterEnabled",
                            "traceControlHeadersEnabled", "diagnosticsLogLevel"
                    },
                    new Object[]{
                            seq, instanceId,
                            true, ratio, routes,
                            true, false, true,
                            true, logLevel
                    });
        } catch (OpenDataException e) {
            throw new AssertionError("Test helper failed to build CompositeData", e);
        }
    }

    /**
     * Builds a CompositeData using the canonical ACCEPTED_COMPLETE type,
     * allowing specific fields to be {@code null} by name.
     * Non-null fields come from the supplied map; entries in {@code nullFields}
     * are explicitly set to {@code null}.
     */
    private CompositeData buildCompleteWith(
            Map<String, Object> nonNullValues,
            String[] nullFields) throws OpenDataException {

        String[] keys = {
                "acceptedSequence", "runtimeInstanceId",
                "samplingEnabled", "samplingRatio", "samplingRouteRatios",
                "validationEnabled", "validationStrict", "exporterEnabled",
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
            case "acceptedSequence"           -> 1L;
            case "runtimeInstanceId"          -> "default-inst";
            case "samplingEnabled",
                 "validationEnabled",
                 "exporterEnabled",
                 "traceControlHeadersEnabled" -> true;
            case "validationStrict"           -> false;
            case "samplingRatio"              -> 1.0;
            case "samplingRouteRatios"        -> emptyRoutes();
            case "diagnosticsLogLevel"        -> "INFO";
            default -> throw new IllegalArgumentException("Unknown key: " + key);
        };
    }

    // =========================================================================
    // Helper — TabularData builders
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
    // Helper — Open-Type constructors
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
                            "acceptedSequence", "runtimeInstanceId",
                            "samplingEnabled", "samplingRatio", "samplingRouteRatios",
                            "validationEnabled", "validationStrict", "exporterEnabled",
                            "traceControlHeadersEnabled", "diagnosticsLogLevel"
                    },
                    new String[]{
                            "acceptedSequence", "runtimeInstanceId",
                            "samplingEnabled", "samplingRatio", "samplingRouteRatios",
                            "validationEnabled", "validationStrict", "exporterEnabled",
                            "traceControlHeadersEnabled", "diagnosticsLogLevel"
                    },
                    new OpenType<?>[]{
                            SimpleType.LONG, SimpleType.STRING,
                            SimpleType.BOOLEAN, SimpleType.DOUBLE, routesType(routeRowType()),
                            SimpleType.BOOLEAN, SimpleType.BOOLEAN, SimpleType.BOOLEAN,
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
                            SimpleType.STRING, SimpleType.INTEGER, SimpleType.STRING, arrayOfStrings()
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
     * Builds a {@code CompositeData} typed exactly as
     * {@code PrivateControlWireTypes.RESPONSE}, defaulting every field to a valid value except
     * those present in {@code nonNullOverrides} (overridden) or {@code nullFields} (forced
     * {@code null}).
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
            case "protocolVersion" -> 1;
            case "runtimeInstanceId" -> "runtime-1";
            case "violationCodes" -> new String[0];
            default -> throw new IllegalArgumentException("Unknown key: " + key);
        };
    }

    // =========================================================================
    // Helper — ConfigServerSettingsManifestBuilder support
    // =========================================================================

    /**
     * Builds a {@link MockEnvironment} containing all 7 required Category A leaves
     * from a PropertySource whose name matches the classifier's authoritative
     * Config Server fragments ({@code configserver:}).
     */
    private static MockEnvironment fullPolicyEnvironment() {
        MockEnvironment env = new MockEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource(
                "configserver:test",
                Map.of(
                        "platform.tracing.policy.sampling.enabled", "true",
                        "platform.tracing.policy.sampling.ratio", "1.0",
                        "platform.tracing.policy.validation.enabled", "true",
                        "platform.tracing.policy.validation.strict", "false",
                        "platform.tracing.policy.exporter.enabled", "true",
                        "platform.tracing.policy.propagation.trace-control-headers.enabled", "true",
                        "platform.tracing.policy.diagnostics.log-level", "INFO"
                )));
        return env;
    }

    /**
     * A route-ratio delivered through a local or unrecognised source is rejected on provenance,
     * so value-level assertions must stage the entry in an authoritative Config Server source.
     */
    private static void putAuthoritativeRouteRatio(MockEnvironment env, String route, String value) {
        env.getPropertySources().addFirst(new MapPropertySource(
                "configserver:test-route-ratios",
                Map.of("platform.tracing.policy.sampling.route-ratios." + route, value)));
    }

    private static ConfigServerSettingsProvenanceClassifier configServerClassifier(
            MockEnvironment env) {
        return new ConfigServerSettingsProvenanceClassifier(env);
    }
}
