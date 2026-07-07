package space.br1440.platform.tracing.semconv.lint.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import space.br1440.platform.tracing.semconv.lint.SpanRecord;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Парсер JSON-формата входных span'ов для линтера.
 * <p>
 * Ожидает один из двух форматов:
 * <ul>
 *   <li><b>JSON-массив</b> объектов span'ов:
 *       <pre>[{"name": "...", "kind": "SERVER", "status": "OK", "attributes": {...}, "resource": {...}}, ...]</pre></li>
 *   <li><b>JSONL</b> (одна строка — один span) с тем же набором полей.</li>
 * </ul>
 * Поле {@code status} принимает значения {@code OK} / {@code ERROR} / {@code UNSET}; если поле
 * отсутствует, считается {@code UNSET}. Значения атрибутов приводятся к строкам через
 * {@link JsonNode#asText()}.
 */
public final class SpanJsonReader {

    private final ObjectMapper mapper;

    public SpanJsonReader() {
        this(new ObjectMapper());
    }

    public SpanJsonReader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<SpanRecord> readFile(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return read(in);
        }
    }

    public List<SpanRecord> read(InputStream input) throws IOException {
        JsonNode root = mapper.readTree(input);
        List<SpanRecord> result = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(node -> result.add(toSpan(node)));
        } else if (root.isObject()) {
            result.add(toSpan(root));
        } else {
            throw new IOException("Ожидался JSON-объект или массив span'ов, получено: " + root.getNodeType());
        }
        return result;
    }

    private SpanRecord toSpan(JsonNode node) {
        String name = textOrEmpty(node, "name");
        String kind = textOrEmpty(node, "kind");
        String status = textOrDefault(node, "status", "UNSET");
        Map<String, String> attributes = toStringMap(node.get("attributes"));
        Map<String, String> resource = toStringMap(node.get("resource"));
        return new SpanRecord(name, kind, status, attributes, resource);
    }

    private static Map<String, String> toStringMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> map = new HashMap<>();
        node.fields().forEachRemaining(entry -> map.put(entry.getKey(), entry.getValue().asText()));
        return map;
    }

    private static String textOrEmpty(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child == null ? "" : child.asText("");
    }

    private static String textOrDefault(JsonNode node, String field, String fallback) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            return fallback;
        }
        String value = child.asText("");
        return value.isEmpty() ? fallback : value;
    }
}
