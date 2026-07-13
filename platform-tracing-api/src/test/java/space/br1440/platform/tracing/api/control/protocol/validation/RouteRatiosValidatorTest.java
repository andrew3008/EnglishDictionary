package space.br1440.platform.tracing.api.control.protocol.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.control.protocol.result.TracingControlProtocolViolation;
import space.br1440.platform.tracing.api.control.protocol.schema.TracingControlProtocolKeys;
import space.br1440.platform.tracing.api.control.protocol.schema.TracingControlProtocolFieldType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct seam tests for {@link RouteRatiosValidator}.
 *
 * <p>These tests verify routeRatios nested-map validation independently of the full
 * validator pipeline, using the real parent key
 * {@link TracingControlProtocolKeys#SAMPLING_ROUTE_RATIOS}.
 *
 * <p>Dependency-direction invariant: {@code RouteRatiosValidator} calls
 * {@code FieldTypeSupport.validateDouble}; {@code FieldTypeSupport} must never
 * reference {@code RouteRatiosValidator}.
 */
@DisplayName("RouteRatiosValidator: routeRatios nested-map validation")
class RouteRatiosValidatorTest {

    private static final String PARENT_KEY = TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS;

    // ─── helpers ────────────────────────────────────────────────────────────────

    private static List<TracingControlProtocolViolation> violations() {
        return new ArrayList<>();
    }

    private static Map<String, Double> validate(Object value,
                                                List<TracingControlProtocolViolation> v) {
        return RouteRatiosValidator.validate(PARENT_KEY, value, v);
    }

    // ─── Shape errors — structural violations use parent key ─────────────────────

    @Test
    @DisplayName("not a Map → TYPE_MISMATCH, reason 'invalid wire type', key = parent key")
    void notMap_returnsTypeMismatch() {
        List<TracingControlProtocolViolation> v = violations();
        Map<String, Double> result = validate("not-a-map", v);

        assertThat(result).isNull();
        assertThat(v).hasSize(1);
        assertThat(v.get(0).code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
        assertThat(v.get(0).reason()).isEqualTo("invalid wire type");
        assertThat(v.get(0).expectedType()).isEqualTo(TracingControlProtocolFieldType.ROUTE_RATIOS_MAP.name());
        assertThat(v.get(0).key()).isEqualTo(PARENT_KEY);
    }

    @Test
    @DisplayName("non-String route key → TYPE_MISMATCH, violation key = parent key (not synthetic)")
    void nonStringRouteKey_returnsTypeMismatch() {
        // Use LinkedHashMap<Object,Object> for deterministic order
        LinkedHashMap<Object, Object> innerMap = new LinkedHashMap<>();
        innerMap.put(42, 0.5); // Integer key, not String

        List<TracingControlProtocolViolation> v = violations();
        @SuppressWarnings("unchecked")
        Map<String, Double> result = validate((Object) (Map<?, ?>) innerMap, v);

        assertThat(result).isNull();
        assertThat(v).hasSize(1);
        assertThat(v.get(0).code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
        assertThat(v.get(0).reason()).isEqualTo("routeRatios map keys must be String");
        assertThat(v.get(0).expectedType()).isEqualTo("Map<String,Double>");
        // Violation key is the parent key, NOT the synthetic nested key
        assertThat(v.get(0).key()).isEqualTo(PARENT_KEY);
    }

    @Test
    @DisplayName("enum route value → TYPE_MISMATCH, violation key = parent key")
    void enumRouteValue_returnsTypeMismatch() {
        LinkedHashMap<String, Object> innerMap = new LinkedHashMap<>();
        innerMap.put("/api", Thread.State.RUNNABLE);

        List<TracingControlProtocolViolation> v = violations();
        Map<String, Double> result = validate(innerMap, v);

        assertThat(result).isNull();
        assertThat(v).hasSize(1);
        assertThat(v.get(0).code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
        assertThat(v.get(0).reason()).isEqualTo("enum instance rejected in routeRatios");
        assertThat(v.get(0).expectedType()).isEqualTo("Double");
        assertThat(v.get(0).key()).isEqualTo(PARENT_KEY);
    }

    // ─── Per-entry value errors — synthetic key ───────────────────────────────────

    @Test
    @DisplayName("wrong-type route value → TYPE_MISMATCH with synthetic key parentKey.'.'routeKey")
    void wrongTypeRouteValue_returnsTypeMismatchWithSyntheticKey() {
        LinkedHashMap<String, Object> innerMap = new LinkedHashMap<>();
        innerMap.put("/api", "bad"); // String, not Double-coercible

        List<TracingControlProtocolViolation> v = violations();
        Map<String, Double> result = validate(innerMap, v);

        assertThat(result).isNull();
        assertThat(v).hasSize(1);
        assertThat(v.get(0).code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
        assertThat(v.get(0).reason()).isEqualTo("invalid wire type");
        assertThat(v.get(0).expectedType()).isEqualTo(TracingControlProtocolFieldType.DOUBLE.name());
        // Synthetic key: parentKey + "." + routeKey
        assertThat(v.get(0).key()).isEqualTo(PARENT_KEY + "." + "/api");
    }

    @Test
    @DisplayName("out-of-range route ratio → TYPE_MISMATCH with synthetic key; reason 'ratio must be in [0.0, 1.0]'")
    void outOfRangeRouteValue_returnsTypeMismatchWithSyntheticKey() {
        LinkedHashMap<String, Object> innerMap = new LinkedHashMap<>();
        innerMap.put("/api", 1.5);

        List<TracingControlProtocolViolation> v = violations();
        Map<String, Double> result = validate(innerMap, v);

        assertThat(result).isNull();
        assertThat(v).hasSize(1);
        assertThat(v.get(0).code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
        assertThat(v.get(0).reason()).isEqualTo("ratio must be in [0.0, 1.0]");
        assertThat(v.get(0).expectedType()).isEqualTo("[0.0, 1.0]");
        assertThat(v.get(0).actualType()).isEqualTo("1.5");
        assertThat(v.get(0).key()).isEqualTo(PARENT_KEY + "." + "/api");
    }

    // ─── Valid maps ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("valid map → normalized to LinkedHashMap<String,Double>, insertion order preserved")
    void validMap_normalizesToLinkedHashMapOfDoubles() {
        LinkedHashMap<String, Object> innerMap = new LinkedHashMap<>();
        innerMap.put("/api", 0.5);
        innerMap.put("/admin", 1);    // Integer — coerced to Double 1.0

        List<TracingControlProtocolViolation> v = violations();
        Map<String, Double> result = validate(innerMap, v);

        assertThat(v).isEmpty();
        assertThat(result).isNotNull();
        assertThat(result).isInstanceOf(LinkedHashMap.class);
        assertThat(result).containsEntry("/api", 0.5d);
        assertThat(result).containsEntry("/admin", 1.0d);
        // Insertion order preserved
        assertThat(result.keySet()).containsExactly("/api", "/admin");
    }

    @Test
    @DisplayName("empty map → valid, normalizes to empty LinkedHashMap")
    void emptyMap_validAndNormalizesToEmptyLinkedHashMap() {
        List<TracingControlProtocolViolation> v = violations();
        Map<String, Double> result = validate(new LinkedHashMap<String, Double>(), v);

        assertThat(v).isEmpty();
        assertThat(result).isNotNull();
        assertThat(result).isInstanceOf(LinkedHashMap.class);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("valid map with Float values coerced to Double")
    void validMap_floatValuesCoercedToDouble() {
        LinkedHashMap<String, Object> innerMap = new LinkedHashMap<>();
        innerMap.put("/api", 0.5f); // Float

        List<TracingControlProtocolViolation> v = violations();
        Map<String, Double> result = validate(innerMap, v);

        assertThat(v).isEmpty();
        assertThat(result).isNotNull();
        assertThat(result.get("/api")).isInstanceOf(Double.class);
        assertThat(result.get("/api")).isCloseTo(0.5d, org.assertj.core.data.Offset.offset(1e-9));
    }
}
