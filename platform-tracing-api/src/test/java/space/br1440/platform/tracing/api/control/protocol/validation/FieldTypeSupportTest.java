package space.br1440.platform.tracing.api.control.protocol.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.control.protocol.result.TracingControlProtocolViolation;
import space.br1440.platform.tracing.api.control.protocol.schema.TracingControlProtocolKeys;
import space.br1440.platform.tracing.api.control.protocol.schema.TracingControlProtocolTypes;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct seam tests for {@link FieldTypeSupport}.
 *
 * <p>Tests in this class target the package-private helper directly, providing
 * independent verification of each scalar/array type validation path. They complement
 * (do not replace) the end-to-end tests in {@code TracingControlProtocolValidatorTest}.
 *
 * <p>Note: {@link TracingControlProtocolTypes#ROUTE_RATIOS_MAP} is deliberately absent
 * from this test class. routeRatios validation belongs to {@code RouteRatiosValidatorTest}.
 * {@code FieldTypeSupport.validateAndNormalize} must never be called for
 * {@code ROUTE_RATIOS_MAP}; any such call throws {@code IllegalStateException}.
 */
@DisplayName("FieldTypeSupport: per-type scalar/array validation")
class FieldTypeSupportTest {

    // ─── helpers ────────────────────────────────────────────────────────────────

    private static List<TracingControlProtocolViolation> violations() {
        return new ArrayList<>();
    }

    private static Object normalize(TracingControlProtocolTypes type, String key, Object value,
                                    List<TracingControlProtocolViolation> v) {
        return FieldTypeSupport.validateAndNormalize(key, type, value, v);
    }

    // ─── STRING ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("STRING: valid string returns same String instance, no violations")
    void stringValid_returnsSameString() {
        List<TracingControlProtocolViolation> v = violations();
        Object result = normalize(TracingControlProtocolTypes.STRING, "source", "hello", v);

        assertThat(result).isEqualTo("hello");
        assertThat(v).isEmpty();
    }

    @Test
    @DisplayName("STRING: wrong type produces TYPE_MISMATCH with reason 'invalid wire type'")
    void stringWrongType_returnsTypeMismatch() {
        List<TracingControlProtocolViolation> v = violations();
        Object result = normalize(TracingControlProtocolTypes.STRING, "source", 42, v);

        assertThat(result).isNull();
        assertThat(v).hasSize(1);
        assertThat(v.get(0).code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
        assertThat(v.get(0).reason()).isEqualTo("invalid wire type");
        assertThat(v.get(0).expectedType()).isEqualTo(TracingControlProtocolTypes.STRING.name());
    }

    @Test
    @DisplayName("validation.mode: unknown value produces TYPE_MISMATCH")
    void validationModeUnknown_returnsTypeMismatch() {
        List<TracingControlProtocolViolation> v = violations();
        Object result = normalize(TracingControlProtocolTypes.STRING,
                TracingControlProtocolKeys.VALIDATION_MODE, "LOUD", v);

        assertThat(result).isNull();
        assertThat(v).hasSize(1);
        assertThat(v.get(0).code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
        assertThat(v.get(0).reason()).isEqualTo("unknown validation.mode wire value");
        assertThat(v.get(0).expectedType()).isEqualTo("STRICT|WARN|DISABLED");
        assertThat(v.get(0).actualType()).isEqualTo("LOUD");
    }

    @Test
    @DisplayName("validation.mode: lowercase 'warn' accepted; returned as 'warn', not canonicalized")
    void validationModeLowercaseAcceptedAndNotCanonicalized() {
        List<TracingControlProtocolViolation> v = violations();
        Object result = normalize(TracingControlProtocolTypes.STRING,
                TracingControlProtocolKeys.VALIDATION_MODE, "warn", v);

        assertThat(result).isEqualTo("warn");   // NOT "WARN"
        assertThat(v).isEmpty();
    }

    // ─── BOOLEAN ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BOOLEAN: wrong type produces TYPE_MISMATCH")
    void booleanWrongType_returnsTypeMismatch() {
        List<TracingControlProtocolViolation> v = violations();
        Object result = normalize(TracingControlProtocolTypes.BOOLEAN, "scrubbing.enabled", "true", v);

        assertThat(result).isNull();
        assertThat(v).hasSize(1);
        assertThat(v.get(0).code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
        assertThat(v.get(0).reason()).isEqualTo("invalid wire type");
        assertThat(v.get(0).expectedType()).isEqualTo(TracingControlProtocolTypes.BOOLEAN.name());
    }

    @Test
    @DisplayName("BOOLEAN: Boolean value accepted")
    void booleanAcceptsBoolean() {
        List<TracingControlProtocolViolation> v = violations();
        Object result = normalize(TracingControlProtocolTypes.BOOLEAN, "scrubbing.enabled", Boolean.TRUE, v);

        assertThat(result).isEqualTo(Boolean.TRUE);
        assertThat(v).isEmpty();
    }

    // ─── INTEGER ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("INTEGER: Integer accepted as-is")
    void integerAcceptsInteger() {
        List<TracingControlProtocolViolation> v = violations();
        Object result = normalize(TracingControlProtocolTypes.INTEGER, "someIntKey", 42, v);

        assertThat(result).isInstanceOf(Integer.class);
        assertThat(result).isEqualTo(42);
        assertThat(v).isEmpty();
    }

    @Test
    @DisplayName("INTEGER: Long within int range coerces to Integer")
    void integerAcceptsLongInRangeAndNarrows() {
        List<TracingControlProtocolViolation> v = violations();
        Object result = normalize(TracingControlProtocolTypes.INTEGER, "someIntKey", 1L, v);

        assertThat(result).isInstanceOf(Integer.class);
        assertThat(result).isEqualTo(1);
        assertThat(v).isEmpty();
    }

    @Test
    @DisplayName("INTEGER: Long out of int range produces TYPE_MISMATCH")
    void integerRejectsLongOutOfRange() {
        List<TracingControlProtocolViolation> v = violations();
        Object result = normalize(TracingControlProtocolTypes.INTEGER, "someIntKey", Long.MAX_VALUE, v);

        assertThat(result).isNull();
        assertThat(v).hasSize(1);
        assertThat(v.get(0).code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
        assertThat(v.get(0).reason()).isEqualTo("invalid wire type");
        assertThat(v.get(0).expectedType()).isEqualTo(TracingControlProtocolTypes.INTEGER.name());
    }

    // ─── LONG ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LONG: Long accepted as-is")
    void longAcceptsLong() {
        List<TracingControlProtocolViolation> v = violations();
        Object result = normalize(TracingControlProtocolTypes.LONG,
                TracingControlProtocolKeys.DIAGNOSTICS_TIMESTAMP, 99L, v);

        assertThat(result).isInstanceOf(Long.class);
        assertThat(result).isEqualTo(99L);
        assertThat(v).isEmpty();
    }

    @Test
    @DisplayName("LONG: Integer widens to Long")
    void longAcceptsIntegerAndWidens() {
        List<TracingControlProtocolViolation> v = violations();
        Object result = normalize(TracingControlProtocolTypes.LONG,
                TracingControlProtocolKeys.DIAGNOSTICS_TIMESTAMP, 42, v);

        assertThat(result).isInstanceOf(Long.class);
        assertThat(result).isEqualTo(42L);
        assertThat(v).isEmpty();
    }

    // ─── DOUBLE ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DOUBLE: Double, Float, Integer, Long all coerce to Double when in [0.0, 1.0]")
    void doubleAcceptsDoubleFloatIntegerLong() {
        String key = TracingControlProtocolKeys.SAMPLING_RATIO;

        // Double
        List<TracingControlProtocolViolation> v1 = violations();
        Object r1 = FieldTypeSupport.validateDouble(key, 0.5d, v1, true);
        assertThat(r1).isInstanceOf(Double.class).isEqualTo(0.5d);
        assertThat(v1).isEmpty();

        // Float
        List<TracingControlProtocolViolation> v2 = violations();
        Object r2 = FieldTypeSupport.validateDouble(key, 0.5f, v2, true);
        assertThat(r2).isInstanceOf(Double.class);
        assertThat((Double) r2).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(v2).isEmpty();

        // Integer — value 1 = 1.0, which is within [0.0, 1.0]
        List<TracingControlProtocolViolation> v3 = violations();
        Object r3 = FieldTypeSupport.validateDouble(key, 1, v3, true);
        assertThat(r3).isInstanceOf(Double.class).isEqualTo(1.0d);
        assertThat(v3).isEmpty();

        // Long — value 1L = 1.0
        List<TracingControlProtocolViolation> v4 = violations();
        Object r4 = FieldTypeSupport.validateDouble(key, 1L, v4, true);
        assertThat(r4).isInstanceOf(Double.class).isEqualTo(1.0d);
        assertThat(v4).isEmpty();
    }

    @Test
    @DisplayName("DOUBLE: wrong type produces TYPE_MISMATCH 'invalid wire type'")
    void doubleRejectsWrongType() {
        List<TracingControlProtocolViolation> v = violations();
        Object result = FieldTypeSupport.validateDouble(
                TracingControlProtocolKeys.SAMPLING_RATIO, "not-a-number", v, true);

        assertThat(result).isNull();
        assertThat(v).hasSize(1);
        assertThat(v.get(0).code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
        assertThat(v.get(0).reason()).isEqualTo("invalid wire type");
        assertThat(v.get(0).expectedType()).isEqualTo(TracingControlProtocolTypes.DOUBLE.name());
    }

    @Test
    @DisplayName("DOUBLE: out-of-range ratio produces TYPE_MISMATCH 'ratio must be in [0.0, 1.0]'")
    void doubleRejectsOutOfRangeRatio() {
        List<TracingControlProtocolViolation> v = violations();
        Object result = FieldTypeSupport.validateDouble(
                TracingControlProtocolKeys.SAMPLING_RATIO, 1.5d, v, true);

        assertThat(result).isNull();
        assertThat(v).hasSize(1);
        assertThat(v.get(0).code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
        assertThat(v.get(0).reason()).isEqualTo("ratio must be in [0.0, 1.0]");
        assertThat(v.get(0).expectedType()).isEqualTo("[0.0, 1.0]");
        assertThat(v.get(0).actualType()).isEqualTo("1.5");
    }

    @Test
    @DisplayName("DOUBLE: out-of-range value accepted when ratioField=false")
    void doubleAllowsOutOfRangeWhenRatioFlagFalse() {
        // ratioField=false: the [0.0, 1.0] bound is not enforced.
        // 1.5 is a valid Double value outside ratio range.
        List<TracingControlProtocolViolation> v = violations();
        Object result = FieldTypeSupport.validateDouble("someDoubleKey", 1.5d, v, false);

        assertThat(result).isInstanceOf(Double.class).isEqualTo(1.5d);
        assertThat(v).isEmpty();
    }

    // ─── STRING_ARRAY ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("STRING_ARRAY: valid non-null String[] accepted")
    void stringArrayAcceptsNonNullElements() {
        List<TracingControlProtocolViolation> v = violations();
        String[] arr = {"on", "true"};
        Object result = normalize(TracingControlProtocolTypes.STRING_ARRAY,
                TracingControlProtocolKeys.SAMPLING_FORCE_HEADER_VALUES, arr, v);

        assertThat(result).isInstanceOf(String[].class);
        assertThat(v).isEmpty();
    }

    @Test
    @DisplayName("STRING_ARRAY: List rejected with 'invalid wire type; use String[] not List or custom type'")
    void stringArrayRejectsList() {
        List<TracingControlProtocolViolation> v = violations();
        Object result = normalize(TracingControlProtocolTypes.STRING_ARRAY,
                TracingControlProtocolKeys.SAMPLING_FORCE_HEADER_VALUES,
                java.util.List.of("a", "b"), v);

        assertThat(result).isNull();
        assertThat(v).hasSize(1);
        assertThat(v.get(0).code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
        assertThat(v.get(0).reason())
                .isEqualTo("invalid wire type; use String[] not List or custom type");
    }

    @Test
    @DisplayName("STRING_ARRAY: null element rejected")
    void stringArrayRejectsNullElement() {
        List<TracingControlProtocolViolation> v = violations();
        Object result = normalize(TracingControlProtocolTypes.STRING_ARRAY,
                TracingControlProtocolKeys.SAMPLING_FORCE_HEADER_VALUES,
                new String[]{"ok", null, "also-ok"}, v);

        assertThat(result).isNull();
        assertThat(v).hasSize(1);
        assertThat(v.get(0).code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
        assertThat(v.get(0).reason()).isEqualTo("String[] must not contain null elements");
        assertThat(v.get(0).expectedType()).isEqualTo("String[]");
        assertThat(v.get(0).actualType()).isEqualTo("null element");
    }

    // ─── Enum rejection (fires before type switch) ────────────────────────────────

    @Test
    @DisplayName("Enum instance rejected before type switch with 'enum instance rejected; use String wire value'")
    void enumInstanceRejectedBeforeTypeSwitch() {
        List<TracingControlProtocolViolation> v = violations();
        // Pass an enum value with expected type STRING — enum guard fires first, not STRING validation.
        Object result = normalize(TracingControlProtocolTypes.STRING,
                TracingControlProtocolKeys.VALIDATION_MODE, Thread.State.RUNNABLE, v);

        assertThat(result).isNull();
        assertThat(v).hasSize(1);
        assertThat(v.get(0).code()).isEqualTo(TracingControlProtocolViolationCode.TYPE_MISMATCH);
        assertThat(v.get(0).reason()).isEqualTo("enum instance rejected; use String wire value");
        assertThat(v.get(0).expectedType()).isEqualTo(TracingControlProtocolTypes.STRING.name());
    }
}
