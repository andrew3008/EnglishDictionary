package space.br1440.platform.tracing.otel.span.builder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.SpanFactory;
import space.br1440.platform.tracing.api.span.builder.RpcClientSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.RpcServerSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.RpcTracing;
import space.br1440.platform.tracing.api.semconv.annotation.RpcSemconvVersion;
import space.br1440.platform.tracing.api.semconv.SemconvValidationMode;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.api.span.spec.SpanRelationship;
import space.br1440.platform.tracing.otel.runtime.RecordingTracingRuntime;
import space.br1440.platform.tracing.otel.semconv.policy.AttributePolicy;
import space.br1440.platform.tracing.otel.semconv.policy.SemconvMetrics;
import space.br1440.platform.tracing.otel.span.DefaultSpanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RpcSpanBuilderTest {

    private RecordingTracingRuntime recording;
    private SpanFactory manual;

    @BeforeEach
    void setUp() {
        recording = new RecordingTracingRuntime();
        AttributePolicy strictPolicy = new AttributePolicy(SemconvValidationMode.STRICT, false, SemconvMetrics.NOOP);
        manual = new DefaultSpanFactory(recording, strictPolicy);
    }

    @Test
    void transportRpc_returnsNonNullEntryPoint() {
        RpcTracing rpc = manual.transport().rpc();
        assertThat(rpc).isNotNull();
        assertThat(rpc.server()).isNotNull();
        assertThat(rpc.client()).isNotNull();
    }

    @Test
    void rpcBuilders_haveExpectedSemconvVersionMarker() {
        final String expected = "1.28.0";
        assertThat(RpcClientSpanBuilder.class.getAnnotation(RpcSemconvVersion.class))
                .isNotNull()
                .extracting(RpcSemconvVersion::value).isEqualTo(expected);
        assertThat(RpcServerSpanBuilder.class.getAnnotation(RpcSemconvVersion.class))
                .isNotNull()
                .extracting(RpcSemconvVersion::value).isEqualTo(expected);

        assertThat(RpcTracing.class.getAnnotation(RpcSemconvVersion.class)).isNull();
    }

    @Test
    void rpcServerStart_routesSpanSpecThroughTracingRuntime() {
        manual.transport().rpc().server()
                .system("grpc")
                .service("OrderService")
                .method("CreateOrder")
                .start()
                .close();

        assertThat(recording.receivedSpecs()).hasSize(1);
        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.category()).isEqualTo(SpanCategory.RPC_SERVER);
        assertThat(spec.reason()).isEqualTo(SpanSpecReason.PLATFORM_EDGE_CASE);
        assertThat(spec.name()).isEqualTo("OrderService/CreateOrder");
        assertThat(spec.attributes()).containsKey("rpc.system");
        assertThat(spec.attributes()).containsKey("rpc.service");
        assertThat(spec.attributes()).containsKey("rpc.method");
    }

    @Test
    void rpcClientStart_routesSpanSpecThroughTracingRuntime() {
        manual.transport().rpc().client()
                .system("grpc")
                .service("OrderService")
                .method("CreateOrder")
                .serverAddress("order-service")
                .start()
                .close();

        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.category()).isEqualTo(SpanCategory.RPC_CLIENT);
        assertThat(spec.attributes()).containsKey("server.address");
    }

    @Test
    void missingSystem_rejected() {
        assertThatThrownBy(() ->
                manual.transport().rpc().server()
                        .service("OrderService")
                        .method("CreateOrder")
                        .start())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("system");
    }

    @Test
    void missingService_rejected() {
        assertThatThrownBy(() ->
                manual.transport().rpc().client()
                        .system("grpc")
                        .method("CreateOrder")
                        .start())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service");
    }

    @Test
    void missingMethod_rejectedInStrictMode() {
        assertThatThrownBy(() ->
                manual.transport().rpc().server()
                        .system("grpc")
                        .service("OrderService")
                        .start())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("method");
    }

    @Test
    void rootRelationship_works() {
        manual.transport().rpc().server()
                .system("grpc")
                .service("OrderService")
                .method("CreateOrder")
                .root()
                .start()
                .close();

        assertThat(recording.receivedSpecs().getFirst().relationship().kind()).isEqualTo(SpanRelationship.ROOT);
    }

    @Test
    void childWithLinks_rejected() {
        RemoteSpanLink link = RemoteSpanLink.sampled(
                "0102030405060708090a0b0c0d0e0f10",
                "0102030405060708");
        assertThatThrownBy(() ->
                manual.transport().rpc().server()
                        .system("grpc")
                        .service("OrderService")
                        .method("CreateOrder")
                        .child()
                        .linkedTo(link)
                        .start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHILD");
    }
}
