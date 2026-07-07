package space.br1440.platform.tracing.e2e.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Минимальный HTTP-клиент Jaeger Query API v3 для e2e/smoke-тестов.
 * <p>
 * Jaeger 1.62+ требует {@code query.start_time_min/max} в формате RFC3339
 * ({@link Instant#toString()}), не unix micros.
 * <p>
 * {@code /api/v3/traces} (FindTraces) — gRPC-gateway endpoint со streaming-ответом:
 * HTTP/JSON может содержать несколько JSON-объектов {@code {"result": ...}} подряд
 * (concatenated, не JSON array). Для smoke-объёмов body читается целиком и парсится
 * через Jackson {@link MappingIterator}.
 *
 * @apiNote Jaeger HTTP JSON {@code /api/v3} помечен как internal API (subject to change).
 *          Для production programmatic access предпочтителен gRPC QueryService (:16685).
 */
public final class JaegerV3QueryClient {

    private final String jaegerQueryBaseUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public JaegerV3QueryClient(String jaegerQueryBaseUrl) {
        this.jaegerQueryBaseUrl = jaegerQueryBaseUrl;
        this.httpClient = new OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(15))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Проверяет, что среди span'ов сервиса есть хотя бы один с указанным {@code operationName}.
     */
    public boolean hasOperation(String serviceName, String operationName) throws Exception {
        String body = fetchTracesBody(serviceName);
        if (body == null || body.isBlank()) {
            return false;
        }
        return parseStreamingBody(body).stream()
                .anyMatch(span -> operationName.equals(span.path("name").asText()));
    }

    /**
     * Возвращает атрибуты первого span'а сервиса, удовлетворяющего предикату.
     */
    public Optional<Map<String, String>> findFirstSpanAttributes(
            String serviceName, Predicate<Map<String, String>> spanMatcher) throws Exception {
        String body = fetchTracesBody(serviceName);
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        for (JsonNode span : parseStreamingBody(body)) {
            Map<String, String> attrs = extractStringAttributes(span);
            if (spanMatcher.test(attrs)) {
                return Optional.of(attrs);
            }
        }
        return Optional.empty();
    }

    /**
     * Ищет span по точному {@code operationName} (поле {@code name} в OTLP JSON).
     */
    public Optional<Map<String, String>> findSpanAttributesByName(
            String serviceName, String operationName) throws Exception {
        String body = fetchTracesBody(serviceName);
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        for (JsonNode span : parseStreamingBody(body)) {
            if (operationName.equals(span.path("name").asText())) {
                return Optional.of(extractStringAttributes(span));
            }
        }
        return Optional.empty();
    }

    /**
     * Span-event OTLP JSON: имя события и его строковые атрибуты. Используется e2e-проверками
     * скрабинга exception-event'ов (Wave A3): {@code exception.message}/{@code exception.stacktrace}
     * не должны утекать при секьюр-дефолте.
     */
    public record SpanEvent(String name, Map<String, String> attributes) {
    }

    /**
     * Возвращает span-events (OTLP {@code spans[].events[]}) первого span'а сервиса с указанным
     * {@code operationName}. Пустой список — span найден, но без событий; для отсутствующего
     * span'а — также пустой список (вызывающий обычно ждёт появления через Awaitility).
     */
    public java.util.List<SpanEvent> findSpanEventsByName(
            String serviceName, String operationName) throws Exception {
        String body = fetchTracesBody(serviceName);
        if (body == null || body.isBlank()) {
            return java.util.List.of();
        }
        for (JsonNode span : parseStreamingBody(body)) {
            if (operationName.equals(span.path("name").asText())) {
                java.util.List<SpanEvent> events = new java.util.ArrayList<>();
                for (JsonNode event : span.path("events")) {
                    events.add(new SpanEvent(
                            event.path("name").asText(),
                            extractAttributesNode(event)));
                }
                return events;
            }
        }
        return java.util.List.of();
    }

    /**
     * Возвращает имена span'ов сервиса (поле {@code name} в OTLP JSON).
     */
    public java.util.List<String> listSpanNames(String serviceName) throws Exception {
        String body = fetchTracesBody(serviceName);
        if (body == null || body.isBlank()) {
            return java.util.List.of();
        }
        java.util.List<String> names = new java.util.ArrayList<>();
        for (JsonNode span : parseStreamingBody(body)) {
            names.add(span.path("name").asText());
        }
        return names;
    }

    /**
     * Возвращает resource-атрибуты (OTLP {@code resourceSpans[].resource.attributes}) первого
     * resourceSpan'а сервиса. Используется для assert'ов resource-идентичности (Фаза 9):
     * {@code service.version}, {@code deployment.environment.name}, {@code platform.c_group} и т.д.
     */
    public Optional<Map<String, String>> findResourceAttributes(String serviceName) throws Exception {
        String body = fetchTracesBody(serviceName);
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try (MappingIterator<JsonNode> iterator = objectMapper
                .readerFor(JsonNode.class)
                .readValues(body)) {
            while (iterator.hasNextValue()) {
                JsonNode chunk = iterator.nextValue();
                JsonNode result = chunk.path("result");
                if (result.isMissingNode() || !result.has("resourceSpans")) {
                    continue;
                }
                for (JsonNode resourceSpan : result.path("resourceSpans")) {
                    JsonNode resource = resourceSpan.path("resource");
                    if (resource.has("attributes")) {
                        return Optional.of(extractAttributesNode(resource));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Извлекает строковые атрибуты из узла, содержащего поле {@code attributes}. */
    private static Map<String, String> extractAttributesNode(JsonNode owner) {
        Map<String, String> result = new LinkedHashMap<>();
        for (JsonNode attr : owner.path("attributes")) {
            String key = attr.path("key").asText();
            JsonNode value = attr.path("value");
            if (value.has("stringValue")) {
                result.put(key, value.path("stringValue").asText());
            }
        }
        return result;
    }

    private String fetchTracesBody(String serviceName) throws IOException {
        String url = findTracesUrl(serviceName);
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return null;
            }
            ResponseBody body = response.body();
            if (body == null) {
                return null;
            }
            return body.string();
        }
    }

    /**
     * Парсит concatenated JSON chunks от Jaeger v3 FindTraces.
     */
    private java.util.List<JsonNode> parseStreamingBody(String body) throws IOException {
        java.util.List<JsonNode> spans = new java.util.ArrayList<>();
        try (MappingIterator<JsonNode> iterator = objectMapper
                .readerFor(JsonNode.class)
                .readValues(body)) {
            while (iterator.hasNextValue()) {
                JsonNode chunk = iterator.nextValue();
                extractSpansFromResult(chunk.path("result"), spans);
            }
        }
        return spans;
    }

    /**
     * Возвращает SERVER-span'ы заданного сервиса, отфильтрованные по {@code http.route},
     * сгруппированные по имени instrumentation scope. Используется для DupSpans HTTP smoke.
     *
     * <p>Внутри одного route ожидается ровно один SERVER-span на instrumentation scope:
     * Agent (например, {@code io.opentelemetry.tomcat-10.0}) и Micrometer Observation
     * (например, {@code org.springframework.boot.actuate.metrics.web.servlet}) — две разные
     * группы. Дублирование = >1 group для одного route.</p>
     */
    /**
     * HTTP-related span'ы для route, сгруппированные по instrumentation scope.
     * Включает SERVER и INTERNAL (Micrometer Observation часто создаёт INTERNAL-дочерний span
     * под Agent SERVER-span'ом при suppress=false + bridge-otel).
     */
    public java.util.Map<String, java.util.List<JsonNode>> findHttpSpansByScopeForRoute(
            String serviceName, String route) throws Exception {
        String body = fetchTracesBody(serviceName);
        java.util.Map<String, java.util.List<JsonNode>> byScope = new java.util.LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return byScope;
        }
        try (MappingIterator<JsonNode> iterator = objectMapper
                .readerFor(JsonNode.class)
                .readValues(body)) {
            while (iterator.hasNextValue()) {
                JsonNode chunk = iterator.nextValue();
                JsonNode result = chunk.path("result");
                if (result.isMissingNode() || !result.has("resourceSpans")) {
                    continue;
                }
                for (JsonNode resourceSpan : result.path("resourceSpans")) {
                    for (JsonNode scopeSpan : resourceSpan.path("scopeSpans")) {
                        String scopeName = scopeSpan.path("scope").path("name").asText();
                        for (JsonNode span : scopeSpan.path("spans")) {
                            if (!isHttpRelatedSpanKind(span.path("kind").asText())) {
                                continue;
                            }
                            Map<String, String> attrs = extractStringAttributes(span);
                            if (matchesRoute(attrs, route) || matchesHttpSpanName(span, route)) {
                                byScope.computeIfAbsent(scopeName, k -> new java.util.ArrayList<>()).add(span);
                            }
                        }
                    }
                }
            }
        }
        return byScope;
    }

    private static boolean isHttpRelatedSpanKind(String kind) {
        return "SPAN_KIND_SERVER".equals(kind) || "2".equals(kind)
                || "SPAN_KIND_INTERNAL".equals(kind) || "1".equals(kind);
    }

    private static boolean matchesHttpSpanName(JsonNode span, String route) {
        String name = span.path("name").asText("");
        return name.contains(route) || "http.server.requests".equals(name);
    }

    /**
     * Сопоставление HTTP-route для Agent (http.route/url.path) и Micrometer Observation (uri).
     */
    private static boolean matchesRoute(Map<String, String> attrs, String route) {
        if (route.equals(attrs.get("http.route")) || route.equals(attrs.get("url.path"))) {
            return true;
        }
        String uri = attrs.get("uri");
        return uri != null && (route.equals(uri) || uri.endsWith(route));
    }

    private static void extractSpansFromResult(JsonNode result, java.util.List<JsonNode> spans) {
        if (result.isMissingNode() || !result.has("resourceSpans")) {
            return;
        }
        for (JsonNode resourceSpan : result.path("resourceSpans")) {
            for (JsonNode scopeSpan : resourceSpan.path("scopeSpans")) {
                for (JsonNode span : scopeSpan.path("spans")) {
                    spans.add(span);
                }
            }
        }
    }

    private String findTracesUrl(String serviceName) {
        Instant now = Instant.now();
        Instant min = now.minus(1, ChronoUnit.HOURS);
        return jaegerQueryBaseUrl + "/api/v3/traces?query.service_name=" + serviceName
                + "&query.start_time_min=" + URLEncoder.encode(min.toString(), StandardCharsets.UTF_8)
                + "&query.start_time_max=" + URLEncoder.encode(now.toString(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> extractStringAttributes(JsonNode span) {
        Map<String, String> result = new LinkedHashMap<>();
        for (JsonNode attr : span.path("attributes")) {
            String key = attr.path("key").asText();
            JsonNode value = attr.path("value");
            if (value.has("stringValue")) {
                result.put(key, value.path("stringValue").asText());
            } else if (value.has("arrayValue")) {
                // Agent пишет capture-request-headers как String[] — в JSON это arrayValue.
                JsonNode values = value.path("arrayValue").path("values");
                if (values.isArray() && !values.isEmpty()) {
                    result.put(key, values.get(0).path("stringValue").asText());
                }
            }
        }
        return result;
    }
}
