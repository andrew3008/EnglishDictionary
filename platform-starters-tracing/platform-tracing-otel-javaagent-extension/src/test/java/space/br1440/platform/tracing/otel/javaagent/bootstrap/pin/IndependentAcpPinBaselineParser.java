package space.br1440.platform.tracing.otel.javaagent.bootstrap.pin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class IndependentAcpPinBaselineParser {

    public static final Set<String> TOP_LEVEL_KEYS = Set.of(
            "formatVersion",
            "coordinate",
            "upstreamSha256",
            "upstreamChecksumSource",
            "pinSha256",
            "pinCount",
            "providers",
            "loaderContract"
    );

    private static final Pattern BINARY_NAME =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*$");
    private static final Pattern SHA256_HEX = Pattern.compile("^[a-f0-9]{64}$");

    private IndependentAcpPinBaselineParser() {
    }

    public static Document parse(byte[] raw) {
        if (raw.length >= 3
                && (raw[0] & 0xFF) == 0xEF
                && (raw[1] & 0xFF) == 0xBB
                && (raw[2] & 0xFF) == 0xBF) {
            throw new IllegalStateException("Pin baseline JSON must not have a UTF-8 BOM");
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        if (text.indexOf('\r') >= 0) {
            throw new IllegalStateException("Pin baseline JSON must be LF-only");
        }
        Map<String, Object> root = new StrictParser(text).parseRoot(TOP_LEVEL_KEYS);
        if (!root.keySet().equals(TOP_LEVEL_KEYS)) {
            throw new IllegalStateException("Pin baseline root keys must be exactly the reviewed set");
        }
        if (!Integer.valueOf(1).equals(root.get("formatVersion"))) {
            throw new IllegalStateException("Unsupported pin formatVersion: " + root.get("formatVersion"));
        }
        String coordinate = requireString(root, "coordinate");
        if (coordinate.isBlank()) {
            throw new IllegalStateException("Pin baseline coordinate is required");
        }
        String upstreamSha256 = requireSha256(root, "upstreamSha256");
        String pinSha256 = requireSha256(root, "pinSha256");
        Object pinCountRaw = root.get("pinCount");
        if (!(pinCountRaw instanceof Integer)) {
            throw new IllegalStateException("Pin baseline pinCount must be a number");
        }
        int pinCount = (Integer) pinCountRaw;
        Object providersRaw = root.get("providers");
        if (!(providersRaw instanceof List<?>)) {
            throw new IllegalStateException("Pin baseline providers must be an array");
        }
        List<?> providers = (List<?>) providersRaw;
        if (pinCount != providers.size()) {
            throw new IllegalStateException("Pin baseline pinCount does not match providers.size()");
        }
        List<String> names = new ArrayList<>();
        String previous = null;
        for (Object item : providers) {
            if (!(item instanceof Map<?, ?>)) {
                throw new IllegalStateException("Pin baseline provider must be an object");
            }
            Map<?, ?> provider = (Map<?, ?>) item;
            Object binaryNameRaw = provider.get("binaryName");
            if (!(binaryNameRaw instanceof String binaryName) || !BINARY_NAME.matcher(binaryName).matches()) {
                throw new IllegalStateException("Pin baseline provider binaryName is malformed: " + binaryNameRaw);
            }
            if (names.contains(binaryName)) {
                throw new IllegalStateException("Pin baseline contains duplicate provider " + binaryName);
            }
            if (previous != null && previous.compareTo(binaryName) >= 0) {
                throw new IllegalStateException("Pin baseline providers are not sorted: " + binaryName);
            }
            previous = binaryName;
            names.add(binaryName);
        }
        if (!pinSha256(names).equals(pinSha256)) {
            throw new IllegalStateException("Pin baseline pinSha256 does not match provider names");
        }
        return new Document(coordinate, upstreamSha256, pinSha256, pinCount, List.copyOf(names));
    }

    private static String pinSha256(List<String> names) {
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(String::compareTo);
        StringBuilder preimage = new StringBuilder();
        for (String name : sorted) {
            preimage.append(name);
            preimage.append('\n');
        }
        return IndependentUpstreamAcpJarExtractor.sha256Hex(
                preimage.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String requireString(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof String text)) {
            throw new IllegalStateException("Pin baseline " + key + " must be a string");
        }
        return text;
    }

    private static String requireSha256(Map<String, Object> object, String key) {
        String value = requireString(object, key);
        if (!SHA256_HEX.matcher(value).matches()) {
            throw new IllegalStateException("Pin baseline " + key + " must be a lowercase SHA-256 hex");
        }
        return value;
    }

    public record Document(
            String coordinate,
            String upstreamSha256,
            String pinSha256,
            int pinCount,
            List<String> providers
    ) {
    }

    private static final class StrictParser {
        private final String text;
        private int index;

        private StrictParser(String text) {
            this.text = text;
            this.index = 0;
        }

        private Map<String, Object> parseRoot(Set<String> allowedKeys) {
            Map<String, Object> parsed = parseObject(allowedKeys);
            skipWhitespace();
            if (index != text.length()) {
                throw new IllegalStateException("Trailing content is not accepted in pin baseline JSON");
            }
            return parsed;
        }

        private Map<String, Object> parseObject(Set<String> allowedKeys) {
            skipWhitespace();
            expect('{');
            Map<String, Object> object = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                index++;
                return object;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                if (!allowedKeys.contains(key)) {
                    throw new IllegalStateException("Unknown pin baseline field: " + key);
                }
                if (object.containsKey(key)) {
                    throw new IllegalStateException("Duplicate pin baseline field: " + key);
                }
                skipWhitespace();
                expect(':');
                skipWhitespace();
                object.put(key, parseValue(key));
                skipWhitespace();
                if (peek() == ',') {
                    index++;
                    continue;
                }
                expect('}');
                return object;
            }
        }

        private Object parseValue(String key) {
            char ch = peek();
            if (ch == '{') {
                if ("upstreamChecksumSource".equals(key)) {
                    return parseObject(Set.of("type", "reference"));
                }
                if ("loaderContract".equals(key)) {
                    return parseObject(Set.of("repository", "tag", "commit", "sources", "conclusionSha256"));
                }
                if ("sources".equals(key)) {
                    return parseObject(Set.of("path", "sha256"));
                }
                return parseObject(Set.of("binaryName", "containerEntry", "descriptorPath"));
            }
            if (ch == '[') {
                return parseArray(key);
            }
            if (ch == '"') {
                return parseString();
            }
            if (ch == '-' || Character.isDigit(ch)) {
                return parseNumber();
            }
            throw new IllegalStateException("Unsupported JSON value at " + index);
        }

        private List<Object> parseArray(String key) {
            expect('[');
            List<Object> values = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                index++;
                return values;
            }
            while (true) {
                skipWhitespace();
                values.add(parseValue(key));
                skipWhitespace();
                if (peek() == ',') {
                    index++;
                    continue;
                }
                expect(']');
                return values;
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (index < text.length()) {
                char ch = text.charAt(index++);
                if (ch == '"') {
                    return value.toString();
                }
                if (ch == '\\') {
                    throw new IllegalStateException("Escaped JSON strings are not accepted in pin baseline");
                }
                if (ch < 0x20) {
                    throw new IllegalStateException("Control characters are not accepted in pin baseline strings");
                }
                value.append(ch);
            }
            throw new IllegalStateException("Unterminated JSON string in pin baseline");
        }

        private Integer parseNumber() {
            int start = index;
            if (peek() == '-') {
                index++;
            }
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            return Integer.parseInt(text.substring(start, index));
        }

        private void skipWhitespace() {
            while (index < text.length()) {
                char ch = text.charAt(index);
                if (ch == ' ' || ch == '\n' || ch == '\t') {
                    index++;
                    continue;
                }
                return;
            }
        }

        private char peek() {
            if (index >= text.length()) {
                throw new IllegalStateException("Unexpected end of pin baseline JSON");
            }
            return text.charAt(index);
        }

        private void expect(char expected) {
            char actual = peek();
            if (actual != expected) {
                throw new IllegalStateException(
                        "Expected '" + expected + "' in pin baseline JSON, got '" + actual + "'");
            }
            index++;
        }
    }
}
