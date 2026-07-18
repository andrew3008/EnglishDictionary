package space.br1440.platform.tracing.api.control.protocol;

import lombok.experimental.UtilityClass;

import java.util.List;

@UtilityClass
final class FieldTypeSupport {

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
            case STRING -> validateString(key, value, violations);
            case BOOLEAN -> validateBoolean(key, value, violations);
            case INTEGER -> validateInteger(key, value, violations);
            case LONG -> validateLong(key, value, violations);
            case DOUBLE -> validateDouble(key, value, violations);
            case STRING_ARRAY -> validateStringArray(key, value, violations);
            case ROUTE_RATIOS_MAP -> throw new IllegalStateException("ROUTE_RATIOS_MAP must be decoded by RouteRatiosNormalizer");
        };
    }

    private static String validateString(String key,
                                         Object value,
                                         List<TracingControlProtocolViolation> violations) {
        if (value instanceof String wireValue) {
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
                                 List<TracingControlProtocolViolation> violations) {
        return switch (value) {
            case Double d -> d;
            case Float f -> f.doubleValue();
            case Integer i -> i.doubleValue();
            case Long l -> l.doubleValue();
            case null, default -> {
                violations.add(violation(
                        key,
                        "invalid wire type",
                        TracingControlProtocolFieldType.DOUBLE.name(),
                        typeName(value),
                        TracingControlProtocolViolationCode.TYPE_MISMATCH));
                yield null;
            }
        };
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
