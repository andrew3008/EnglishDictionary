package space.br1440.platform.tracing.otel.span.builder;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.otel.runtime.otel.OtelTracingRuntimeFactory;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

class RpcSpanBuilderIntegrationTest {

    private static final String INVALID_SPAN_ID = "0000000000000000";
    private static final String RPC_SPAN_NAME = "OrderService/CreateOrder";

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
        try (@SuppressWarnings("unused") var parent = tracing.spans().operation("parent").start()) {
            String parentTraceId = tracing.traceContext().traceId().orElseThrow();
            String parentSpanId = tracing.traceContext().spanId().orElseThrow();
            tracing.spans().transport().rpc().server()
                    .system("grpc")
                    .service("OrderService")
                    .method("CreateOrder")
                    .start()
                    .close();

            SpanData child = finishedRpcSpan();
            assertThat(child.getTraceId()).isEqualTo(parentTraceId);
            assertThat(child.getParentSpanId()).isEqualTo(parentSpanId);
            assertThat(child.getKind()).isEqualTo(SpanKind.SERVER);
            assertThat(child.getAttributes().get(key(PlatformAttributes.RPC_SYSTEM_NAME))).isEqualTo("grpc");
            assertThat(child.getAttributes().get(key(PlatformAttributes.RPC_SERVICE))).isEqualTo("OrderService");
            assertThat(child.getAttributes().get(key(PlatformAttributes.RPC_METHOD))).isEqualTo("CreateOrder");
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

        SpanData span = finishedRpcSpan();
        assertThat(span.getParentSpanId()).isIn("", INVALID_SPAN_ID);
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(span.getAttributes().get(key(PlatformAttributes.RPC_SYSTEM_NAME))).isEqualTo("grpc");
        assertThat(span.getAttributes().get(key(PlatformAttributes.RPC_SERVICE))).isEqualTo("OrderService");
        assertThat(span.getAttributes().get(key(PlatformAttributes.RPC_METHOD))).isEqualTo("CreateOrder");
        assertThat(span.getAttributes().get(key("server.address"))).isEqualTo("orders.example.com");
    }

    private static io.opentelemetry.api.common.AttributeKey<String> key(String name) {
        return io.opentelemetry.api.common.AttributeKey.stringKey(name);
    }

    private SpanData finishedRpcSpan() {
        List<SpanData> spans = exporter.getFinishedSpanItems();
        return spans.stream()
                .filter(span -> RPC_SPAN_NAME.equals(span.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Span not found: " + RPC_SPAN_NAME));
    }
}
