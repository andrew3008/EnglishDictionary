package space.br1440.platform.tracing.otel.javaagent.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
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

class ExporterBoundaryRealPayloadTest {

    private static final String EMAIL_CANARY = "canary@example.com";
    private static final String PASSWORD_CANARY = "hunter2-canary";

    private static final List<SpanAttributeScrubbingRule> RULES = List.of(
            BuiltInSpanAttributeScrubbingRules.EMAIL.create(),
            BuiltInSpanAttributeScrubbingRules.PASSWORD_KEY.create()
    );

    private static SpanData spanWithSecretsEverywhere() {
        return ExporterBoundaryTestSupport.baseSpan("GET /login " + EMAIL_CANARY)
                .setStatus(StatusData.create(StatusCode.ERROR, "failed for " + EMAIL_CANARY))
                .setEvents(List.of(EventData.create(
                        1L,
                        "login attempt for " + EMAIL_CANARY,
                        Attributes.of(AttributeKey.stringKey("password"), PASSWORD_CANARY))))
                .setLinks(List.of(LinkData.create(
                        ExporterBoundaryTestSupport.LINK_TARGET,
                        Attributes.of(AttributeKey.stringKey("contact"), EMAIL_CANARY))))
                .build();
    }

    /** Захватывает именно отрендеренную запись (сообщение вместе с подставленными параметрами). */
    private static Handler capturingHandler(List<String> sink) {
        SimpleFormatter formatter = new SimpleFormatter();
        return new Handler() {
            @Override
            public void publish(LogRecord record) {
                sink.add(formatter.format(record));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }

    @Test
    @DisplayName("Реальный OTLP payload не содержит сырых значений")
    void otlpPayloadCarriesNoRawSecrets() {
        // given
        List<String> rendered = new CopyOnWriteArrayList<>();
        Logger julLogger = Logger.getLogger(OtlpJsonLoggingSpanExporter.class.getName());
        Handler capture = capturingHandler(rendered);
        Level previousLevel = julLogger.getLevel();
        julLogger.addHandler(capture);
        julLogger.setLevel(Level.ALL);

        try (SpanExporter delegate = OtlpJsonLoggingSpanExporter.create()) {
            ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(RULES);
            SpanExporter exporter = new SafeSpanExporter(
                    delegate, TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()), runtime);

            // when
            exporter.export(List.of(spanWithSecretsEverywhere()));
        } finally {
            julLogger.removeHandler(capture);
            julLogger.setLevel(previousLevel);
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
        Logger julLogger = Logger.getLogger(LoggingSpanExporter.class.getName());
        List<String> rendered = new CopyOnWriteArrayList<>();
        Handler capture = capturingHandler(rendered);
        Level previousLevel = julLogger.getLevel();
        julLogger.addHandler(capture);
        julLogger.setLevel(Level.ALL);

        try (LoggingSpanExporter delegate = LoggingSpanExporter.create()) {
            ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(RULES);
            SpanExporter exporter = new SafeSpanExporter(
                    delegate, TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()), runtime);

            // when
            exporter.export(List.of(spanWithSecretsEverywhere()));
        } finally {
            julLogger.removeHandler(capture);
            julLogger.setLevel(previousLevel);
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
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(RULES);
        SpanExporter exporter = new SafeSpanExporter(
                delegate, TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()), runtime);

        List<SpanData> finished;
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
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
        Logger julLogger = Logger.getLogger(OtlpJsonLoggingSpanExporter.class.getName());
        Handler capture = capturingHandler(rendered);
        Level previousLevel = julLogger.getLevel();
        julLogger.addHandler(capture);
        julLogger.setLevel(Level.ALL);

        try (SpanExporter delegate = OtlpJsonLoggingSpanExporter.create()) {
            // when
            recordExceptionThrough(delegate);
        } finally {
            julLogger.removeHandler(capture);
            julLogger.setLevel(previousLevel);
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

    /** Настоящий SDK-путь: {@code recordException} на живом span, затем boundary и транспорт. */
    private static void recordExceptionThrough(SpanExporter delegate) {
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(RULES);
        SpanExporter exporter = new SafeSpanExporter(
                delegate, TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()), runtime);

        try (SdkTracerProvider provider = SdkTracerProvider.builder()
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
