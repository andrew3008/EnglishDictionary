package space.br1440.platform.tracing.otel.javaagent.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.configuration.OtelSdkDefaults;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.exporter.boundary.BoundaryTestSupport;
import space.br1440.platform.tracing.otel.javaagent.exporter.guard.OtlpRequestSizeLimitingSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.processor.ScrubbingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.ExceptionEventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

@ResourceLock("io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingSpanExporter")
class ExporterBoundaryRealPayloadTest {

    private static final String EMAIL_CANARY = "canary@example.com";
    private static final String PASSWORD_CANARY = "hunter2-canary";
    private static final String URL_MESSAGE = "Request failed: https://url-user:url-password-canary@service.example/path"
            + "?token=url-query-canary&mode=debug#url-fragment-canary";
    private static final String MASKED_URL_MESSAGE = "Request failed: https://***@service.example/path"
            + "?token=***&mode=***#***";

    private static final List<SpanAttributeScrubbingRule> RULES = List.of(
            BuiltInSpanAttributeScrubbingRules.URL_CREDENTIALS.create(),
            BuiltInSpanAttributeScrubbingRules.EMAIL.create(),
            BuiltInSpanAttributeScrubbingRules.PASSWORD_KEY.create()
    );

    private static SpanData spanWithSecretsEverywhere() {
        return ExporterTestSpans.baseSpan("GET /login " + EMAIL_CANARY)
                .setStatus(StatusData.create(StatusCode.ERROR, "failed for " + EMAIL_CANARY))
                .setEvents(List.of(EventData.create(
                        1L,
                        "login attempt for " + EMAIL_CANARY,
                        Attributes.of(AttributeKey.stringKey("password"), PASSWORD_CANARY))))
                .setLinks(List.of(LinkData.create(
                        ExporterTestSpans.LINK_TARGET,
                        Attributes.of(AttributeKey.stringKey("contact"), EMAIL_CANARY))))
                .build();
    }

    @Test
    @DisplayName("Реальный OTLP payload не содержит сырых значений")
    void otlpPayloadCarriesNoRawSecrets() {
        // given
        List<String> rendered = new CopyOnWriteArrayList<>();
        try (ExporterLogCapture ignored = new ExporterLogCapture(OtlpJsonLoggingSpanExporter.class, rendered);
             ScrubbingRuntime runtime = BoundaryTestSupport.runtimeOf(RULES);
             SpanExporter exporter = safeExporter(OtlpJsonLoggingSpanExporter.create(), runtime)) {

            // when
            exporter.export(List.of(spanWithSecretsEverywhere()));
        }

        // then
        String payload = String.join("\n", rendered);
        assertThat(payload)
                .as("payload не пустой — иначе проверка отсутствия секретов ничего не значит")
                .contains("scopeSpans");
        assertThat(payload).doesNotContain(EMAIL_CANARY).doesNotContain(PASSWORD_CANARY);
    }

    @Test
    @DisplayName("Отрендеренный вывод LoggingSpanExporter (сообщение + параметры) не содержит секретов")
    void loggingExporterRenderedOutputCarriesNoRawSecrets() {
        // given
        List<String> rendered = new CopyOnWriteArrayList<>();
        try (ExporterLogCapture ignored = new ExporterLogCapture(LoggingSpanExporter.class, rendered);
             ScrubbingRuntime runtime = BoundaryTestSupport.runtimeOf(RULES);
             SpanExporter exporter = safeExporter(LoggingSpanExporter.create(), runtime)) {

            // when
            exporter.export(List.of(spanWithSecretsEverywhere()));
        }

        // then
        assertThat(rendered).isNotEmpty();
        assertThat(String.join("\n", rendered))
                .doesNotContain(EMAIL_CANARY)
                .doesNotContain(PASSWORD_CANARY);
    }

    @Test
    @DisplayName("Настоящий Span.recordException канонизируется без Throwable и без исходного сообщения")
    void realRecordExceptionIsCanonicalizedAtTheBoundary() {
        // given
        InMemorySpanExporter delegate = InMemorySpanExporter.create();
        ScrubbingRuntime runtime = BoundaryTestSupport.runtimeOf(RULES);
        SpanExporter exporter = safeExporter(delegate, runtime);

        List<SpanData> finished;
        try (runtime; SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            Tracer tracer = provider.get("test");
            Span span = tracer.spanBuilder("checkout").startSpan();
            // when
            span.recordException(new IllegalStateException("login failed for " + EMAIL_CANARY));
            span.end();

            finished = List.copyOf(delegate.getFinishedSpanItems());
        }

        // then
        assertThat(finished).hasSize(1);
        List<EventData> events = finished.getFirst().getEvents();
        assertThat(events).hasSize(1);

        EventData exceptionEvent = events.getFirst();
        assertThat(exceptionEvent)
                .as("ExceptionEventData удерживает исходный Throwable — на boundary он обязан исчезнуть")
                .isNotInstanceOf(ExceptionEventData.class);
        assertThat(exceptionEvent.getAttributes().asMap().values().toString())
                .doesNotContain(EMAIL_CANARY);
        assertThat(runtime.boundaryMetrics().exceptionEventsCanonicalized()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Span.recordException -> boundary -> реальный OTLP JSON: сырых значений нет в сериализованном виде")
    void realRecordExceptionIsAbsentFromTheSerializedJsonPayload() {
        // given
        List<String> rendered = new CopyOnWriteArrayList<>();
        try (ExporterLogCapture ignored = new ExporterLogCapture(OtlpJsonLoggingSpanExporter.class, rendered);
             SpanExporter delegate = OtlpJsonLoggingSpanExporter.create()) {
            // when
            recordExceptionThrough(delegate);
        }

        // then
        String payload = String.join("\n", rendered);
        assertThat(payload)
                .as("payload не пустой — иначе проверка отсутствия секретов ничего не значит")
                .contains("scopeSpans")
                .as("канонизированное exception-событие обязано остаться наблюдаемым")
                .contains("exception.type");
        assertThat(payload)
                .doesNotContain(EMAIL_CANARY)
                .doesNotContain("login failed");
    }

    @Test
    @DisplayName("Span.recordException -> boundary -> реальный бинарный OTLP: канарейки нет в байтах запроса")
    void realRecordExceptionIsAbsentFromTheSerializedBinaryPayload() throws Exception {
        // given
        List<byte[]> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                bodies.add(in.readAllBytes());
            }
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();

        try (SpanExporter delegate = OtlpHttpSpanExporter.builder()
                .setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces")
                .setCompression("none")
                .build()) {
            // when
            recordExceptionThrough(delegate);
        } finally {
            server.stop(0);
        }

        // then
        assertThat(bodies).isNotEmpty();
        String wire = new String(concat(bodies), StandardCharsets.UTF_8);
        assertThat(wire)
                .as("payload не пустой — иначе проверка отсутствия секретов ничего не значит")
                .contains("exception.type");
        assertThat(wire)
                .doesNotContain(EMAIL_CANARY)
                .doesNotContain("login failed");
    }

    @Test
    void exceptionUrlCredentialsAreMaskedInTheSerializedJsonPayload() {
        // given
        List<String> rendered = new CopyOnWriteArrayList<>();
        try (ExporterLogCapture ignored = new ExporterLogCapture(OtlpJsonLoggingSpanExporter.class, rendered);
             SpanExporter delegate = OtlpJsonLoggingSpanExporter.create()) {
            // when
            recordUrlFailureThrough(delegate);
        }

        // then
        String payload = String.join("\n", rendered);
        assertThat(payload).contains("scopeSpans", "exception.message")
                .containsSubsequence(MASKED_URL_MESSAGE, MASKED_URL_MESSAGE)
                .doesNotContain("url-user", "url-password-canary", "url-query-canary", "url-fragment-canary", "debug");
    }

    @Test
    void exceptionUrlCredentialsAreMaskedInTheSerializedBinaryPayload() throws Exception {
        // given
        List<byte[]> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                bodies.add(in.readAllBytes());
            }
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();

        try (SpanExporter delegate = OtlpHttpSpanExporter.builder()
                .setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces")
                .setCompression("none")
                .build()) {
            // when
            recordUrlFailureThrough(delegate);
        } finally {
            server.stop(0);
        }

        // then
        assertThat(bodies).isNotEmpty();
        String wire = new String(concat(bodies), StandardCharsets.UTF_8);
        assertThat(wire).contains("exception.message", "exception.type")
                .containsSubsequence(MASKED_URL_MESSAGE, MASKED_URL_MESSAGE)
                .doesNotContain("url-user", "url-password-canary", "url-query-canary", "url-fragment-canary", "debug");
    }

    @Test
    void urlMaskingSurvivesNoOpTruncationBeforeExport() {
        // given
        SpanAttributeScrubbingRule lengthLimit = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override
            public String name() {
                return "message-length-limit";
            }

            @Nonnull
            @Override
            public ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
                return "exception.message".equals(key)
                        ? ScrubbingDecision.truncate(name(), 512)
                        : ScrubbingDecision.keep();
            }
        };
        ScrubbingRuntime runtime = BoundaryTestSupport.runtimeOf(
                List.of(BuiltInSpanAttributeScrubbingRules.URL_CREDENTIALS.create()), List.of(lengthLimit), null);
        InMemorySpanExporter delegate = InMemorySpanExporter.create();
        SpanExporter exporter = safeExporter(delegate, runtime);
        List<SpanData> finished;

        try (runtime; SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(new ScrubbingSpanProcessor(runtime))
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            Span span = provider.get("test").spanBuilder("url-truncation").startSpan();
            span.setAttribute("exception.message", URL_MESSAGE);

            // when
            span.end();
            finished = List.copyOf(delegate.getFinishedSpanItems());
        }

        // then
        assertThat(finished).singleElement().satisfies(span ->
                assertThat(span.getAttributes().get(AttributeKey.stringKey("exception.message")))
                        .isEqualTo(MASKED_URL_MESSAGE));
        assertThat(runtime.attributesVerified()).isTrue();
        assertThat(runtime.boundaryMetrics().spansChanged()).isZero();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "array-value-scan-hmac-key")
    void stringArraySecretsAreScrubbedInTheSerializedJsonPayload(String hmacKey) throws Exception {
        // given
        String jwt = "eyJhbGciOiJIUzI1NiJ9." + "p".repeat(513) + ".signature-canary";
        String emailNeighbor = "email-neighbor-canary";
        String jwtNeighbor = "jwt-neighbor-canary";
        Attributes attributes = Attributes.builder()
                .put(AttributeKey.stringArrayKey("participants"), List.of(EMAIL_CANARY, emailNeighbor))
                .put(AttributeKey.stringArrayKey("messages"), List.of(jwt, jwtNeighbor))
                .put(AttributeKey.stringArrayKey("combined"), List.of(EMAIL_CANARY, jwt))
                .build();
        ScrubbingRuntime runtime = BoundaryTestSupport.runtimeOf(List.of(
                BuiltInSpanAttributeScrubbingRules.JWT.create(),
                BuiltInSpanAttributeScrubbingRules.EMAIL.create()), hmacKey);
        String expectedEmail = hmacKey == null ? "***" : runtime.hmacHex(EMAIL_CANARY);
        String expectedNeighbor = hmacKey == null ? "***" : runtime.hmacHex(emailNeighbor);
        String doubleHashedEmail = hmacKey == null ? null : runtime.hmacHex(expectedEmail);
        List<String> rendered = new CopyOnWriteArrayList<>();

        // when
        try (runtime;
             ExporterLogCapture ignored = new ExporterLogCapture(OtlpJsonLoggingSpanExporter.class, rendered);
             SdkTracerProvider provider = SdkTracerProvider.builder()
                     .addSpanProcessor(new ScrubbingSpanProcessor(runtime))
                     .addSpanProcessor(SimpleSpanProcessor.create(
                             safeExporter(OtlpJsonLoggingSpanExporter.create(), runtime)))
                     .build()) {
            Span span = provider.get("test").spanBuilder("value-scan-arrays")
                    .addLink(ExporterTestSpans.LINK_TARGET, attributes)
                    .startSpan();
            span.setAllAttributes(attributes);
            span.addEvent("array-event", attributes);
            span.end();
            assertThat(provider.forceFlush().join(10, TimeUnit.SECONDS).isSuccess()).isTrue();
        }

        // then
        assertThat(rendered).hasSize(1);
        String payload = rendered.getFirst();
        assertThat(payload).contains("scopeSpans")
                .doesNotContain(EMAIL_CANARY, jwt, emailNeighbor, jwtNeighbor);
        JsonNode span = new ObjectMapper().readTree(payload).path("scopeSpans").path(0).path("spans").path(0);
        assertThat(span.path("name").asText()).isEqualTo("value-scan-arrays");
        assertThat(span.path("events").size()).isEqualTo(1);
        assertThat(span.path("links").size()).isEqualTo(1);
        JsonNode event = span.path("events").path(0);
        JsonNode link = span.path("links").path(0);
        assertThat(event.path("name").asText()).isEqualTo("array-event");
        for (JsonNode owner : List.of(span, event, link)) {
            assertThat(stringArrayAttribute(owner, "participants")).containsExactly(expectedEmail, expectedNeighbor);
        }
        assertThat(stringArrayAttribute(span, "messages")).containsExactly("", "");
        assertThat(stringArrayAttribute(span, "combined")).containsExactly("", "");
        for (JsonNode owner : List.of(event, link)) {
            assertThat(attributeValue(owner, "messages")).isNull();
            assertThat(attributeValue(owner, "combined")).isNull();
        }
        if (hmacKey != null) {
            assertThat(payload).doesNotContain(doubleHashedEmail);
        }
        assertThat(runtime.attributesVerified()).isTrue();
        assertThat(runtime.boundaryMetrics().criticalFailures()).isZero();
    }

    @Test
    void overBudgetStringArraysCollapseWithoutAffectingNumericArraysInTheSerializedJsonPayload() throws Exception {
        // given
        String longNeighbor = "aggregate-neighbor-canary" + "x".repeat(4060);
        Attributes attributes = Attributes.builder()
                .put(AttributeKey.stringArrayKey("many"), Collections.nCopies(10_000, "element-count-canary"))
                .put(AttributeKey.stringArrayKey("long"), List.of(EMAIL_CANARY, longNeighbor))
                .put(AttributeKey.longArrayKey("numbers"), Collections.nCopies(33, 42L))
                .build();
        ScrubbingRuntime runtime = BoundaryTestSupport.runtimeOf(List.of(
                BuiltInSpanAttributeScrubbingRules.JWT.create(),
                BuiltInSpanAttributeScrubbingRules.EMAIL.create()), "array-value-scan-hmac-key");
        String hashedEmail = runtime.hmacHex(EMAIL_CANARY);
        List<String> rendered = new CopyOnWriteArrayList<>();

        // when
        try (runtime;
             ExporterLogCapture ignored = new ExporterLogCapture(OtlpJsonLoggingSpanExporter.class, rendered);
             SdkTracerProvider provider = SdkTracerProvider.builder()
                     .addSpanProcessor(new ScrubbingSpanProcessor(runtime))
                     .addSpanProcessor(SimpleSpanProcessor.create(
                             safeExporter(OtlpJsonLoggingSpanExporter.create(), runtime)))
                     .build()) {
            Span span = provider.get("test").spanBuilder("value-scan-budgets")
                    .addLink(ExporterTestSpans.LINK_TARGET, attributes)
                    .startSpan();
            span.setAllAttributes(attributes);
            span.addEvent("budget-event", attributes);
            span.end();
            assertThat(provider.forceFlush().join(10, TimeUnit.SECONDS).isSuccess()).isTrue();
        }

        // then
        assertThat(rendered).hasSize(1);
        String payload = rendered.getFirst();
        assertThat(payload).contains("scopeSpans")
                .doesNotContain(EMAIL_CANARY, "element-count-canary", "aggregate-neighbor-canary", hashedEmail);
        JsonNode span = new ObjectMapper().readTree(payload).path("scopeSpans").path(0).path("spans").path(0);
        assertThat(span.path("name").asText()).isEqualTo("value-scan-budgets");
        assertThat(span.path("events").size()).isEqualTo(1);
        assertThat(span.path("links").size()).isEqualTo(1);
        JsonNode event = span.path("events").path(0);
        assertThat(event.path("name").asText()).isEqualTo("budget-event");
        for (JsonNode owner : List.of(span, event, span.path("links").path(0))) {
            assertThat(stringArrayAttribute(owner, "many")).containsExactly("***");
            assertThat(stringArrayAttribute(owner, "long")).containsExactly("***");
            JsonNode numbersValue = attributeValue(owner, "numbers");
            assertThat(numbersValue).isNotNull();
            JsonNode numbers = numbersValue.path("arrayValue").path("values");
            assertThat(numbers.size()).isEqualTo(33);
            for (JsonNode number : numbers) {
                assertThat(number.path("intValue").asLong()).isEqualTo(42L);
            }
        }
        assertThat(runtime.maskedActions()).isEqualTo(2L);
        assertThat(runtime.hashedActions()).isZero();
        assertThat(runtime.failures()).isZero();
        assertThat(runtime.attributesVerified()).isTrue();
        assertThat(runtime.boundaryMetrics().criticalFailures()).isZero();
    }

    @Nullable
    private static JsonNode attributeValue(JsonNode owner, String key) {
        for (JsonNode attribute : owner.path("attributes")) {
            if (attribute.path("key").asText().equals(key)) {
                return attribute.path("value");
            }
        }
        return null;
    }

    private static List<String> stringArrayAttribute(JsonNode owner, String key) {
        JsonNode value = attributeValue(owner, key);
        assertThat(value).isNotNull();
        List<String> elements = new ArrayList<>();
        for (JsonNode element : value.path("arrayValue").path("values")) {
            JsonNode stringValue = element.path("stringValue");
            assertThat(stringValue.isTextual()).as("stringValue in array %s", key).isTrue();
            elements.add(stringValue.asText());
        }
        return elements;
    }

    private static SpanExporter safeExporter(SpanExporter delegate, ScrubbingRuntime runtime) {
        return new SafeSpanExporter(
                new OtlpRequestSizeLimitingSpanExporter(delegate, Math.toIntExact(OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES)),
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()), runtime);
    }

    private static void recordUrlFailureThrough(SpanExporter delegate) {
        ScrubbingRuntime runtime = BoundaryTestSupport.runtimeOf(RULES);
        SpanExporter exporter = safeExporter(delegate, runtime);

        try (runtime; SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            Span span = provider.get("test").spanBuilder("url-failure").startSpan();
            span.addEvent("exception", Attributes.of(
                    AttributeKey.stringKey("exception.type"), IllegalStateException.class.getName(),
                    AttributeKey.stringKey("exception.message"), URL_MESSAGE));
            span.setStatus(StatusCode.ERROR, URL_MESSAGE);
            span.end();
            assertThat(provider.forceFlush().join(10, TimeUnit.SECONDS).isSuccess()).isTrue();
        }

        assertThat(runtime.boundaryMetrics().eventsScrubbed()).isEqualTo(1L);
        assertThat(runtime.boundaryMetrics().spansChanged()).isEqualTo(1L);
        assertThat(runtime.boundaryMetrics().criticalFailures()).isZero();
    }

    private static void recordExceptionThrough(SpanExporter delegate) {
        ScrubbingRuntime runtime = BoundaryTestSupport.runtimeOf(RULES);
        SpanExporter exporter = safeExporter(delegate, runtime);

        try (runtime; SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            Span span = provider.get("test").spanBuilder("checkout").startSpan();
            span.recordException(new IllegalStateException("login failed for " + EMAIL_CANARY));
            span.end();
            provider.forceFlush().join(10, TimeUnit.SECONDS);
        }

        assertThat(runtime.boundaryMetrics().exceptionEventsCanonicalized()).isEqualTo(1L);
    }

    private static byte[] concat(List<byte[]> chunks) {
        int total = chunks.stream().mapToInt(chunk -> chunk.length).sum();
        byte[] merged = new byte[total];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, merged, offset, chunk.length);
            offset += chunk.length;
        }
        return merged;
    }
}
