package space.br1440.platform.tracing.bench.baseline;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.TreeMap;

final class JmhResultKey {

    private JmhResultKey() {
    }

    static String canonicalKey(JsonNode entry) {
        String benchmark = textOrEmpty(entry, "benchmark");
        String mode = textOrEmpty(entry, "mode");
        return benchmark + "|" + mode + "|" + canonicalParams(entry.get("params"));
    }

    private static String textOrEmpty(JsonNode entry, String field) {
        JsonNode value = entry.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    /**
     * Matches historical Groovy {@code entry.params.sort().toString()} for baseline compatibility,
     * e.g. {@code [depth:10]}.
     */
    static String canonicalParams(JsonNode params) {
        if (params == null || params.isNull() || !params.isObject() || params.isEmpty()) {
            return "";
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        params.properties().forEach(entry -> sorted.put(entry.getKey(), paramValue(entry.getValue())));
        if (sorted.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (!first) {
                builder.append(", ");
            }
            builder.append(entry.getKey()).append(':').append(entry.getValue());
            first = false;
        }
        builder.append(']');
        return builder.toString();
    }

    private static String paramValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return "null";
        }
        if (value.isNumber()) {
            return value.asText();
        }
        if (value.isBoolean()) {
            return value.asText();
        }
        return value.asText();
    }
}
