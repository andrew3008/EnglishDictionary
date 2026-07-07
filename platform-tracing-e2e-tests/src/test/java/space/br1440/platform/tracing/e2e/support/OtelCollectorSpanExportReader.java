package space.br1440.platform.tracing.e2e.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Чтение NDJSON-экспорта OTel Collector file exporter для smoke-assertion'ов.
 * <p>
 * Каждая строка файла — отдельный JSON-объект (OTLP JSON encoding).
 */
public final class OtelCollectorSpanExportReader {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private OtelCollectorSpanExportReader() {
    }

    /**
     * Извлекает плоские string-атрибуты всех span'ов из NDJSON-файла Collector'а.
     */
    public static List<Map<String, String>> readAllSpanStringAttributes(Path ndjsonFile) throws IOException {
        String content = Files.readString(ndjsonFile);
        List<Map<String, String>> result = new ArrayList<>();
        try (MappingIterator<JsonNode> iterator = OBJECT_MAPPER
                .readerFor(JsonNode.class)
                .readValues(content)) {
            while (iterator.hasNextValue()) {
                JsonNode document = iterator.nextValue();
                appendSpansFromDocument(document, result);
            }
        }
        return result;
    }

    /**
     * Возвращает число span'ов с точным значением string-атрибута.
     */
    public static long countSpansByAttributeValue(
            List<Map<String, String>> spans, String attributeKey, String expectedValue) {
        return spans.stream()
                .filter(attrs -> expectedValue.equals(attrs.get(attributeKey)))
                .count();
    }

    private static void appendSpansFromDocument(JsonNode document, List<Map<String, String>> result) {
        JsonNode resourceSpans = document.path("resourceSpans");
        if (!resourceSpans.isArray()) {
            return;
        }
        for (JsonNode resourceSpan : resourceSpans) {
            for (JsonNode scopeSpan : resourceSpan.path("scopeSpans")) {
                for (JsonNode span : scopeSpan.path("spans")) {
                    result.add(extractStringAttributes(span));
                }
            }
        }
    }

    private static Map<String, String> extractStringAttributes(JsonNode span) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (JsonNode attr : span.path("attributes")) {
            String key = attr.path("key").asText();
            JsonNode value = attr.path("value");
            if (value.has("stringValue")) {
                attributes.put(key, value.path("stringValue").asText());
            }
        }
        return attributes;
    }
}
