package space.br1440.platform.tracing.jmx.contract.tool;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class ContractValues {

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value, String path) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw invalidType(path, "map");
        }

        for (Object key : raw.keySet()) {
            if (!(key instanceof String)) {
                throw invalidType(path + ".<key>", "string");
            }
        }

        return (Map<String, Object>) raw;
    }

    public static List<Object> list(Object value, String path) {
        if (!(value instanceof List<?> raw)) {
            throw invalidType(path, "list");
        }

        return new ArrayList<>(raw);
    }

    public static String string(Object value, String path) {
        if (!(value instanceof String result) || result.isBlank()) {
            throw invalidType(path, "non-blank string");
        }

        return result;
    }

    public static int integer(Object value, String path) {
        if (!(value instanceof Number number)
                || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != number.intValue()) {
            throw invalidType(path, "integer");
        }

        return number.intValue();
    }

    public static void bool(Object value, String path) {
        if (!(value instanceof Boolean)) {
            throw invalidType(path, "boolean");
        }
    }

    public static List<String> strings(Object value, String path) {
        List<Object> values = list(value, path);
        List<String> result = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            result.add(string(values.get(index), path + '[' + index + ']'));
        }

        return result;
    }

    public static String javaConstantName(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isUpperCase(character) && index > 0
                    && result.charAt(result.length() - 1) != '_') {
                result.append('_');
            }

            result.append(
                    Character.isLetterOrDigit(character)
                    ? Character.toUpperCase(character)
                    : '_'
            );
        }

        return result.toString();
    }

    public static void requireValue(Map<String, Object> values, String key, String expected, String path) {
        String actual = string(values.get(key), path + '.' + key);
        if (!expected.equals(actual)) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_VALUE_UNSUPPORTED",
                    path + '.' + key + " must be " + expected
            );
        }
    }

    public static void assertKeys(
            Map<String, Object> values, String path, Set<String> allowed, Set<String> required
    ) {
        Set<String> unknown = new LinkedHashSet<>(values.keySet());
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_UNKNOWN_FIELD",
                    path + " has unknown fields: " + unknown
            );
        }

        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(values.keySet());
        if (!missing.isEmpty()) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_REQUIRED_FIELD_MISSING",
                    path + " is missing fields: " + missing
            );
        }
    }

    private static ContractValidationException invalidType(String path, String expected) {
        return new ContractValidationException(
                "JMX_SCHEMA_FIELD_TYPE_MISMATCH",
                path + " must be a " + expected
        );
    }
}
