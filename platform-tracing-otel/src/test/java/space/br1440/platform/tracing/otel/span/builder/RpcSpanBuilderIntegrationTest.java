package space.br1440.platform.tracing.otel.span.builder;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.runtime.otel.OtelTracingRuntimeFactory;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RpcSpanBuilderIntegrationTest {

    private static final String INVALID_SPAN_ID = "0000000000000000";

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private DefaultTraceOperations tracing;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        tracing = new DefaultTraceOperations(OtelTracingRuntimeFactory.create(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()));
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
        assertThat(tracing.traceContext().traceId()).isEmpty();
        assertThat(tracing.traceContext().spanId()).isEmpty();
    }

    @Test
    void rpcServerChild_insideActiveParent_isChildWithSemconvAttributes() {
        try (var parent = tracing.spans().operation("parent").start()) {
            String parentTraceId = tracing.traceContext().traceId().orElseThrow();
            String parentSpanId = tracing.traceContext().spanId().orElseThrow();
            tracing.spans().transport().rpc().server()
                    .system("grpc")
                    .service("OrderService")
                    .method("CreateOrder")
                    .start()
                    .close();

            SpanData child = findSpan("OrderService/CreateOrder");
            assertThat(child.getTraceId()).isEqualTo(parentTraceId);
            assertThat(child.getParentSpanId()).isEqualTo(parentSpanId);
            assertThat(child.getKind()).isEqualTo(SpanKind.SERVER);
            assertThat(child.getAttributes().get(key("rpc.system"))).isEqualTo("grpc");
            assertThat(child.getAttributes().get(key("rpc.service"))).isEqualTo("OrderService");
            assertThat(child.getAttributes().get(key("rpc.method"))).isEqualTo("CreateOrder");
        }
    }

    @Test
    void rpcClientRoot_isRootWithSemconvAttributes() {
        tracing.spans().transport().rpc().client()
                .system("grpc")
                .service("OrderService")
                .method("CreateOrder")
                .serverAddress("orders.example.com")
                .root()
                .start()
                .close();

        SpanData span = findSpan("OrderService/CreateOrder");
        assertThat(span.getParentSpanId()).isIn("", INVALID_SPAN_ID);
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(span.getAttributes().get(key("rpc.system"))).isEqualTo("grpc");
        assertThat(span.getAttributes().get(key("rpc.service"))).isEqualTo("OrderService");
        assertThat(span.getAttributes().get(key("rpc.method"))).isEqualTo("CreateOrder");
        assertThat(span.getAttributes().get(key("server.address"))).isEqualTo("orders.example.com");
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
