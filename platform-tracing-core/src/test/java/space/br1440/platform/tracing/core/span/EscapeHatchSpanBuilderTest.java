package space.br1440.platform.tracing.core.span;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.semconv.SemconvViolationException;
import space.br1440.platform.tracing.api.semconv.ValidationMode;
import space.br1440.platform.tracing.api.span.SpanScope;
import space.br1440.platform.tracing.core.exception.ExceptionRecorder;
import space.br1440.platform.tracing.core.impl.DefaultTracingImplementation;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;
import space.br1440.platform.tracing.core.semconv.SemconvMetrics;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class EscapeHatchSpanBuilderTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private OpenTelemetrySdk sdk;
    private Tracer tracer;
    private AttributePolicy policy;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
        tracer = sdk.getTracer(DefaultTracingImplementation.INSTRUMENTATION_NAME);
        policy = new AttributePolicy();
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
    }

    @Test
    void httpServer_kind_иИмя_method_route() {
        try (SpanScope ignored = new HttpServerSpanBuilderImpl(tracer, policy, ExceptionRecorder.secureDefault())
                .method("GET").route("/users/{id}").start()) {
        }
        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
        assertThat(span.getName()).isEqualTo("GET /users/{id}");
        assertThat(span.getAttributes().get(SemconvKeys.HTTP_REQUEST_METHOD)).isEqualTo("GET");
        assertThat(span.getAttributes()
                .get(io.opentelemetry.api.common.AttributeKey.stringKey("platform.trace.type")))
                .isEqualTo("http_server");
    }

    @Test
    void httpClient_kind_иСанитизированныйUrl() {
        try (SpanScope ignored = new HttpClientSpanBuilderImpl(tracer, policy, ExceptionRecorder.secureDefault())
                .method("POST")
                .url("https://user:pass@host/p?token=secret")
                .start()) {
        }
        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(span.getName()).isEqualTo("POST");
        String url = span.getAttributes().get(SemconvKeys.URL_FULL);
        assertThat(url).doesNotContain("pass").doesNotContain("secret").contains("REDACTED");
    }

    @Test
    void database_имяOperationCollection_иSqlБезЛитералов_неВИмени() {
        try (SpanScope ignored = new DatabaseSpanBuilderImpl(tracer, policy, ExceptionRecorder.secureDefault())
                .system("postgresql")
                .collection("orders")
                .operation("SELECT")
                .statement("SELECT * FROM orders WHERE id = 42 AND ssn = '123'")
                .start()) {
        }
        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(span.getName()).isEqualTo("SELECT orders");
        assertThat(span.getName()).doesNotContain("42").doesNotContain("123");
        String statement = span.getAttributes().get(AttributeKey.stringKey("db.statement"));
        assertThat(statement).doesNotContain("42").doesNotContain("123").contains("?");
    }

    @Test
    void rpcClient_имяServiceMethod_kindClient() {
        try (SpanScope ignored = new RpcClientSpanBuilderImpl(tracer, policy, ExceptionRecorder.secureDefault())
                .system("grpc").service("orders.OrderService").method("Get").start()) {
        }
        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(span.getName()).isEqualTo("orders.OrderService/Get");
    }

    @Test
    void kafkaProducer_kindProducer_имяDestinationOperation_systemKafka() {
        try (SpanScope ignored = new KafkaProducerSpanBuilderImpl(tracer, policy, ExceptionRecorder.secureDefault())
                .destination("orders").operation("publish").start()) {
        }
        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getKind()).isEqualTo(SpanKind.PRODUCER);
        assertThat(span.getName()).isEqualTo("orders publish");
        assertThat(span.getAttributes().get(SemconvKeys.MESSAGING_SYSTEM)).isEqualTo("kafka");
    }

    @Test
    void kafkaConsumer_kindConsumer() {
        try (SpanScope ignored = new KafkaConsumerSpanBuilderImpl(tracer, policy, ExceptionRecorder.secureDefault())
                .destination("orders").operation("process").start()) {
        }
        assertThat(exporter.getFinishedSpanItems().get(0).getKind()).isEqualTo(SpanKind.CONSUMER);
    }

    @Test
    void rpcServer_kindServer() {
        try (SpanScope ignored = new RpcServerSpanBuilderImpl(tracer, policy, ExceptionRecorder.secureDefault())
                .system("grpc").service("S").method("M").start()) {
        }
        assertThat(exporter.getFinishedSpanItems().get(0).getKind()).isEqualTo(SpanKind.SERVER);
    }

    @Test
    void antiDouble_reEntryТойЖеКатегории_деградируетВEnrich_безНовогоSpan() {
        HttpServerSpanBuilderImpl builder = new HttpServerSpanBuilderImpl(tracer, policy, ExceptionRecorder.secureDefault());
        try (SpanScope outer = builder.method("GET").route("/a").start()) {
            SpanScope reentry = builder.method("GET").route("/a").start();
            reentry.close();
            assertThat(exporter.getFinishedSpanItems()).isEmpty();
        }
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    void antiDouble_forceNewSpan_создаётОтдельныйSpan() {
        HttpServerSpanBuilderImpl builder = new HttpServerSpanBuilderImpl(tracer, policy, ExceptionRecorder.secureDefault());
        try (SpanScope outer = builder.method("GET").route("/a").start()) {
            try (SpanScope inner = builder.method("GET").route("/b").forceNewSpan().start()) {
            }
        }
        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);
    }

    @Test
    void strict_обязательныйАтрибутОтсутствует_бросаетSemconvViolationException() {
        AttributePolicy strict = new AttributePolicy(ValidationMode.STRICT, false, SemconvMetrics.NOOP);
        assertThatExceptionOfType(SemconvViolationException.class).isThrownBy(() ->
                new RpcServerSpanBuilderImpl(tracer, strict, ExceptionRecorder.secureDefault())
                        .system("grpc").service("S").start());
    }
}
