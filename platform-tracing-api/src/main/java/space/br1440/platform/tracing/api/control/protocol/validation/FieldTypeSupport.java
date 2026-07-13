package space.br1440.platform.tracing.api.control.protocol.validation;

import lombok.experimental.UtilityClass;
import space.br1440.platform.tracing.api.control.protocol.result.TracingControlProtocolViolation;
import space.br1440.platform.tracing.api.control.protocol.schema.TracingControlProtocolKeys;
import space.br1440.platform.tracing.api.control.protocol.schema.TracingControlProtocolFieldType;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

@UtilityClass
final class FieldTypeSupport {

    private static final Set<String> VALIDATION_MODES = Set.of("STRICT", "WARN", "DISABLED");

    static Object validateAndNormalize(String key,
                                       TracingControlProtocolFieldType expectedType,
                                       Object value,
                                       List<TracingControlProtocolViolation> violations) {
        if (value instanceof Enum<?>) {
            violations.add(violation(
                    key,
                    "enum instance rejected; use String wire value",
                    expectedType.name(),
                    typeName(value),
                    TracingControlProtocolViolationCode.TYPE_MISMATCH));
            return null;
        }

        return switch (expectedType) {
            case STRING -> validateString(key, value, allowedValuesFor(key), violations);
            case BOOLEAN -> validateBoolean(key, value, violations);
            case INTEGER -> validateInteger(key, value, violations);
            case LONG -> validateLong(key, value, violations);
            case DOUBLE -> validateDouble(key, value, violations, expectedType.ratioBounded());
            case STRING_ARRAY -> validateStringArray(key, value, violations);
            case ROUTE_RATIOS_MAP -> throw new IllegalStateException(
                    "ROUTE_RATIOS_MAP must not be dispatched through FieldTypeSupport; "
                            + "call RouteRatiosValidator directly from the orchestrator.");
        };
    }

    private static Predicate<String> allowedValuesFor(String key) {
        if (TracingControlProtocolKeys.VALIDATION_MODE.equals(key)) {
            return FieldTypeSupport::isKnownValidationMode;
        }

        return null;
    }

    private static boolean isKnownValidationMode(String mode) {
        return VALIDATION_MODES.stream().anyMatch(m -> m.equalsIgnoreCase(mode));
    }

    private static String validateString(String key,
                                         Object value,
                                         Predicate<String> allowedValues,
                                         List<TracingControlProtocolViolation> violations) {
        if (value instanceof String wireValue) {
            if (allowedValues != null && !allowedValues.test(wireValue)) {
                if (TracingControlProtocolKeys.VALIDATION_MODE.equals(key)) {
                    violations.add(violation(
                            key,
                            "unknown validation.mode wire value",
                            "STRICT|WARN|DISABLED",
                            wireValue,
                            TracingControlProtocolViolationCode.TYPE_MISMATCH));
                } else {
                    violations.add(violation(
                            key,
                            "unknown wire value for key",
                            "<caller-defined allowed set>",
                            wireValue,
                            TracingControlProtocolViolationCode.TYPE_MISMATCH));
                }
                return null;
            }

            return wireValue;
        }

        violations.add(violation(
                key,
                "invalid wire type",
                TracingControlProtocolFieldType.STRING.name(),
                typeName(value),
                TracingControlProtocolViolationCode.TYPE_MISMATCH));
        return null;
    }

    private static Boolean validateBoolean(String key,
                                           Object value,
                                           List<TracingControlProtocolViolation> violations) {
        if (value instanceof Boolean b) {
            return b;
        }

        violations.add(violation(
                key,
                "invalid wire type",
                TracingControlProtocolFieldType.BOOLEAN.name(),
                typeName(value),
                TracingControlProtocolViolationCode.TYPE_MISMATCH));
        return null;
    }

    private static Integer validateInteger(String key,
                                           Object value,
                                           List<TracingControlProtocolViolation> violations) {
        if (value instanceof Integer i) {
            return i;
        }

        if (value instanceof Long l && (l >= Integer.MIN_VALUE) && (l <= Integer.MAX_VALUE)) {
            return l.intValue();
        }

        violations.add(violation(
                key,
                "invalid wire type",
                TracingControlProtocolFieldType.INTEGER.name(),
                typeName(value),
                TracingControlProtocolViolationCode.TYPE_MISMATCH));
        return null;
    }

    private static Long validateLong(String key,
                                     Object value,
                                     List<TracingControlProtocolViolation> violations) {
        if (value instanceof Long l) {
            return l;
        }

        if (value instanceof Integer i) {
            return i.longValue();
        }

        violations.add(violation(
                key,
                "invalid wire type",
                TracingControlProtocolFieldType.LONG.name(),
                typeName(value),
                TracingControlProtocolViolationCode.TYPE_MISMATCH));
        return null;
    }

    static Double validateDouble(String key,
                                 Object value,
                                 List<TracingControlProtocolViolation> violations,
                                 boolean ratioField) {
        Double ratio;
        switch (value) {
            case Double d -> ratio = d;
            case Float f -> ratio = f.doubleValue();
            case Integer i -> ratio = i.doubleValue();
            case Long l -> ratio = l.doubleValue();
            case null, default -> {
                violations.add(violation(
                        key,
                        "invalid wire type",
                        TracingControlProtocolFieldType.DOUBLE.name(),
                        typeName(value),
                        TracingControlProtocolViolationCode.TYPE_MISMATCH));
                return null;
            }
        }

        if (ratioField && (ratio < 0.0 || ratio > 1.0)) {
            violations.add(violation(
                    key,
                    "ratio must be in [0.0, 1.0]",
                    "[0.0, 1.0]",
                    String.valueOf(ratio),
                    TracingControlProtocolViolationCode.TYPE_MISMATCH));
            return null;
        }

        return ratio;
    }

    private static String[] validateStringArray(String key,
                                                Object value,
                                                List<TracingControlProtocolViolation> violations) {
        if (!(value instanceof String[] wireValues)) {
            violations.add(violation(
                    key,
                    "invalid wire type; use String[] not List or custom type",
                    TracingControlProtocolFieldType.STRING_ARRAY.name(),
                    typeName(value),
                    TracingControlProtocolViolationCode.TYPE_MISMATCH));
            return null;
        }

        for (String element : wireValues) {
            if (element == null) {
                violations.add(violation(
                        key,
                        "String[] must not contain null elements",
                        "String[]",
                        "null element",
                        TracingControlProtocolViolationCode.TYPE_MISMATCH));
                return null;
            }
        }

        return wireValues;
    }

    static TracingControlProtocolViolation violation(String key,
                                                     String reason,
                                                     String expectedType,
                                                     String actualType,
                                                     TracingControlProtocolViolationCode code) {
        return new TracingControlProtocolViolation(key, reason, expectedType, actualType, code);
    }

    static String typeName(Object value) {
        return (value == null) ? "null" : value.getClass().getName();
    }
}