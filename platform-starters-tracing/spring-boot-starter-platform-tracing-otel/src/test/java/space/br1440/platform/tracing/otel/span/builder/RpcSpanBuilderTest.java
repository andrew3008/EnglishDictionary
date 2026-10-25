package space.br1440.platform.tracing.otel.span.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.attributes.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.api.semconv.annotation.RpcSemconvVersion;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanFactory;
import space.br1440.platform.tracing.api.span.builder.RpcClientSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.RpcServerSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.RpcTracing;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanRelationship;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidationMetrics;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidator;
import space.br1440.platform.tracing.otel.runtime.RecordingTracingRuntime;
import space.br1440.platform.tracing.otel.span.DefaultSpanFactory;

class RpcSpanBuilderTest {

    private RecordingTracingRuntime recording;
    private SpanFactory manual;

    @BeforeEach
    void setUp() {
        recording = new RecordingTracingRuntime();
        SpanAttributeValidator strictPolicy = new SpanAttributeValidator(
                SpanAttributeValidationLevel.ERROR,
                SpanAttributeValidationMetrics.NOOP
        );

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
        try (SpanHandle handle = manual.transport().rpc().server()
                .system("grpc")
                .service("OrderService")
                .method("CreateOrder")
                .start()) {
            assertThat(handle).isNotNull();
        }

        assertThat(recording.receivedSpecs()).hasSize(1);
        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.category()).isEqualTo(SpanCategory.RPC_SERVER);
        assertThat(spec.reason()).isEmpty();
        assertThat(spec.name()).isEqualTo("OrderService/CreateOrder");
        assertThat(spec.attributes()).containsKey(PlatformAttributes.RPC_SYSTEM_NAME);
        assertThat(spec.attributes()).containsKey(PlatformAttributes.RPC_SERVICE);
        assertThat(spec.attributes()).containsKey(PlatformAttributes.RPC_METHOD);
    }

    @Test
    void rpcClientStart_routesSpanSpecThroughTracingRuntime() {
        try (SpanHandle handle = manual.transport().rpc().client()
                .system("grpc")
                .service("OrderService")
                .method("CreateOrder")
                .serverAddress("order-service")
                .start()) {
            assertThat(handle).isNotNull();
        }

        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.category()).isEqualTo(SpanCategory.RPC_CLIENT);
        assertThat(spec.attributes()).containsKey("server.address");
    }

    @Test
    void missingSystem_rejected() {
        assertThatThrownBy(() -> {
            try (SpanHandle handle = manual.transport().rpc().server()
                    .service("OrderService")
                    .method("CreateOrder")
                    .start()) {
                fail("missing RPC system must fail at start(), got " + handle);
            }
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("system");
    }

    @Test
    void missingService_rejected() {
        assertThatThrownBy(() -> {
            try (SpanHandle handle = manual.transport().rpc().client()
                    .system("grpc")
                    .method("CreateOrder")
                    .start()) {
                fail("missing rpc.service must fail at start(), got " + handle);
            }
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service");
    }

    @Test
    void missingMethod_rejectedAtStart() {
        assertThatThrownBy(() -> {
            try (SpanHandle handle = manual.transport().rpc().server()
                    .system("grpc")
                    .service("OrderService")
                    .start()) {
                fail("missing rpc.method must fail at start(), got " + handle);
            }
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("method");
    }

    @Test
    void rootRelationship_works() {
        try (SpanHandle handle = manual.transport().rpc().server()
                .system("grpc")
                .service("OrderService")
                .method("CreateOrder")
                .root()
                .start()) {
            assertThat(handle).isNotNull();
        }

        assertThat(recording.receivedSpecs().getFirst().relationship().kind()).isEqualTo(SpanRelationship.ROOT);
    }

    @Test
    void childWithLinks_rejected() {
        RemoteSpanLink link = RemoteSpanLink.sampled(
                "0102030405060708090a0b0c0d0e0f10",
                "0102030405060708");
        assertThatThrownBy(() -> {
            try (SpanHandle handle = manual.transport().rpc().server()
                    .system("grpc")
                    .service("OrderService")
                    .method("CreateOrder")
                    .child()
                    .linkedTo(link)
                    .start()) {
                fail("CHILD with links must fail at start(), got " + handle);
            }
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHILD");
    }
}
