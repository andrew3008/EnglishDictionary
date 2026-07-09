package space.br1440.platform.tracing.core.manual;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.core.runtime.otel.OtelTracingRuntimeFactory;
import space.br1440.platform.tracing.core.facade.DefaultPlatformTracing;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HttpSpanBuilderIntegrationTest {

    private static final String INVALID_SPAN_ID = "0000000000000000";

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private DefaultPlatformTracing tracing;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        tracing = new DefaultPlatformTracing(OtelTracingRuntimeFactory.create(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()));
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
        assertThat(tracing.traceContext().traceId()).isEmpty();
        assertThat(tracing.traceContext().spanId()).isEmpty();
    }

    @Test
    void httpServerChild_insideActiveParent_isChildWithSemconvAttributes() {
        try (var parent = tracing.manual().operation("parent").start()) {
            String parentTraceId = tracing.traceContext().traceId().orElseThrow();
            String parentSpanId = tracing.traceContext().spanId().orElseThrow();
            tracing.manual().transport().http().server()
                    .method("GET")
                    .route("/api/items")
                    .start()
                    .close();

            SpanData child = findSpan("GET /api/items");
            assertThat(child.getTraceId()).isEqualTo(parentTraceId);
            assertThat(child.getParentSpanId()).isEqualTo(parentSpanId);
            assertThat(child.getKind()).isEqualTo(SpanKind.SERVER);
            assertThat(child.getAttributes().get(key("http.request.method"))).isEqualTo("GET");
            assertThat(child.getAttributes().get(key("http.route"))).isEqualTo("/api/items");
        }
    }

    @Test
    void httpClientRoot_isRootWithSemconvAttributes() {
        tracing.manual().transport().http().client()
                .method("POST")
                .url("https://example.com/api")
                .serverAddress("example.com")
                .root()
                .start()
                .close();

        SpanData span = findSpan("POST");
        assertThat(span.getParentSpanId()).isIn("", INVALID_SPAN_ID);
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(span.getAttributes().get(key("http.request.method"))).isEqualTo("POST");
        assertThat(span.getAttributes().get(key("url.full"))).isEqualTo("https://example.com/api");
        assertThat(span.getAttributes().get(key("server.address"))).isEqualTo("example.com");
    }

    private static io.opentelemetry.api.common.AttributeKey<String> key(String name) {
        return io.opentelemetry.api.common.AttributeKey.stringKey(name);
    }

    private SpanData findSpan(String name) {
        List<SpanData> spans = exporter.getFinishedSpanItems();
        return spans.stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Span not found: " + name));
    }
}
