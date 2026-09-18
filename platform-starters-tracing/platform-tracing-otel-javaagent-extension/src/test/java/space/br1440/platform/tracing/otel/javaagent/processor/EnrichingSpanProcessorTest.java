package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.test.junit.OtelSdkExtension;
import space.br1440.platform.tracing.test.junit.internal.ScopeMode;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;

class EnrichingSpanProcessorTest {

    private static final AttributeKey<String> PLATFORM_TYPE_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_TYPE);
    private static final AttributeKey<String> PLATFORM_RESULT_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT);
    private static final AttributeKey<String> PLATFORM_HOST_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_HOST);
    private static final AttributeKey<String> PLATFORM_REMOTE_SERVICE_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_REMOTE_SERVICE);

    @RegisterExtension
    static OtelSdkExtension otel = OtelSdkExtension.builder()
            .scope(ScopeMode.METHOD)
            .addSpanProcessor(new EnrichingSpanProcessor())
            .build();

    @Test
    void setsPlatformTypeFromSpanKind(OpenTelemetrySdk sdk,
                                      InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("test");

        // when
        Span span = tracer.spanBuilder("server-op").setSpanKind(SpanKind.SERVER).startSpan();
        span.end();

        // then
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().asMap())
                .containsEntry(PLATFORM_TYPE_KEY, "http_server")
                .containsEntry(PLATFORM_RESULT_KEY, "success");
    }

    @Test
    void doesNotSetPlatformHostOnSpan(OpenTelemetrySdk sdk,
                                      InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("test");

        // when
        Span span = tracer.spanBuilder("op").setSpanKind(SpanKind.SERVER).startSpan();
        span.end();

        // then
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(PLATFORM_HOST_KEY)).isNull();
    }

    @Test
    void doesNotOverwriteExistingPlatformType(OpenTelemetrySdk sdk,
                                              InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("test");

        // when
        Span span = tracer.spanBuilder("internal-op").setSpanKind(SpanKind.INTERNAL).startSpan();
        span.setAttribute(PlatformAttributes.PLATFORM_TYPE, "database");
        span.end();

        // then
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(PLATFORM_TYPE_KEY)).isEqualTo("database");
    }

    @Test
    void setsPlatformResultFailureOnStatusCodeError(OpenTelemetrySdk sdk,
                                                    InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("test");

        // when
        Span span = tracer.spanBuilder("op").setSpanKind(SpanKind.CLIENT).startSpan();
        span.setStatus(StatusCode.ERROR, "boom");
        span.end();

        // then
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(PLATFORM_RESULT_KEY)).isEqualTo("failure");
    }

    @Test
    void setsPlatformRemoteServiceFromPeerServiceOnClientError(OpenTelemetrySdk sdk,
                                                               InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("test");

        // when
        Span span = tracer.spanBuilder("client-call").setSpanKind(SpanKind.CLIENT).startSpan();
        span.setAttribute("peer.service", "order-service");
        span.setAttribute("server.address", "10.0.0.1");
        span.setStatus(StatusCode.ERROR, "boom");
        span.end();

        // then
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(PLATFORM_REMOTE_SERVICE_KEY)).isEqualTo("order-service");
    }

    @Test
    void platformRemoteServiceFallsBackToRpcService(OpenTelemetrySdk sdk,
                                                    InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("test");

        // when
        Span span = tracer.spanBuilder("grpc-call").setSpanKind(SpanKind.CLIENT).startSpan();
        span.setAttribute("rpc.service", "io.platform.OrderService");
        span.setStatus(StatusCode.ERROR, "boom");
        span.end();

        // then
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(PLATFORM_REMOTE_SERVICE_KEY)).isEqualTo("io.platform.OrderService");
    }

    @Test
    void doesNotSetPlatformRemoteServiceForSuccessfulClientCall(OpenTelemetrySdk sdk,
                                                                InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("test");

        // when
        Span span = tracer.spanBuilder("ok-call").setSpanKind(SpanKind.CLIENT).startSpan();
        span.setAttribute("peer.service", "billing-service");
        span.end();

        // then
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(PLATFORM_REMOTE_SERVICE_KEY)).isNull();
    }

    @Test
    void doesNotSetPlatformRemoteServiceForServerSpanWithError(OpenTelemetrySdk sdk,
                                                               InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("test");

        // when
        Span span = tracer.spanBuilder("server-op").setSpanKind(SpanKind.SERVER).startSpan();
        span.setAttribute("peer.service", "order-service");
        span.setStatus(StatusCode.ERROR, "boom");
        span.end();

        // then
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(PLATFORM_REMOTE_SERVICE_KEY)).isNull();
    }

    @Test
    void platformRemoteServiceIgnoresIpServerAddress(OpenTelemetrySdk sdk,
                                                     InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("test");

        // when
        Span span = tracer.spanBuilder("ip-only").setSpanKind(SpanKind.CLIENT).startSpan();
        span.setAttribute("server.address", "192.168.1.10");
        span.setStatus(StatusCode.ERROR, "boom");
        span.end();

        // then
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(PLATFORM_REMOTE_SERVICE_KEY)).isNull();
    }

    @Test
    void platformRemoteServiceUsesServerAddressWithHostnameAndPort(OpenTelemetrySdk sdk,
                                                                   InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("test");

        // when
        Span span = tracer.spanBuilder("dns-port").setSpanKind(SpanKind.CLIENT).startSpan();
        span.setAttribute("server.address", "billing.svc.cluster.local:8080");
        span.setStatus(StatusCode.ERROR, "boom");
        span.end();

        // then
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(PLATFORM_REMOTE_SERVICE_KEY))
                .isEqualTo("billing.svc.cluster.local:8080");
    }

    @Test
    void platformRemoteServiceIgnoresPureIpv6Address(OpenTelemetrySdk sdk,
                                                     InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("test");

        // when
        Span span = tracer.spanBuilder("ipv6-only").setSpanKind(SpanKind.CLIENT).startSpan();
        span.setAttribute("server.address", "2001:db8::1");
        span.setStatus(StatusCode.ERROR, "boom");
        span.end();

        // then
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(PLATFORM_REMOTE_SERVICE_KEY)).isNull();
    }

    @Test
    void platformRemoteServiceUsesShortDnsName(OpenTelemetrySdk sdk,
                                               InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("test");

        // when
        Span span = tracer.spanBuilder("short-host").setSpanKind(SpanKind.CLIENT).startSpan();
        span.setAttribute("server.address", "billing");
        span.setStatus(StatusCode.ERROR, "boom");
        span.end();

        // then
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(PLATFORM_REMOTE_SERVICE_KEY)).isEqualTo("billing");
    }

    @Test
    void platformRemoteServiceUsesServerAddressAsDnsName(OpenTelemetrySdk sdk,
                                                         InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("test");

        // when
        Span span = tracer.spanBuilder("dns-only").setSpanKind(SpanKind.CLIENT).startSpan();
        span.setAttribute("server.address", "billing.svc.cluster.local");
        span.setStatus(StatusCode.ERROR, "boom");
        span.end();

        // then
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(PLATFORM_REMOTE_SERVICE_KEY))
                .isEqualTo("billing.svc.cluster.local");
    }

    @Test
    void overridesPlatformTypeToDatabaseWhenStableDbSystemNamePresent(OpenTelemetrySdk sdk,
                                                                      InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("test");

        // when
        Span span = tracer.spanBuilder("SELECT orders").setSpanKind(SpanKind.CLIENT).startSpan();
        span.setAttribute("db.system.name", "postgresql");
        span.end();

        // then
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(PLATFORM_TYPE_KEY)).isEqualTo("database");
    }

    @Test
    void overridesPlatformTypeToDatabaseWhenLegacyDbSystemPresent(OpenTelemetrySdk sdk,
                                                                  InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("test");

        // when
        Span span = tracer.spanBuilder("SELECT users").setSpanKind(SpanKind.CLIENT).startSpan();
        span.setAttribute("db.system", "postgresql");
        span.end();

        // then
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(PLATFORM_TYPE_KEY)).isEqualTo("database");
    }

    @Test
    void overridesPlatformTypeToDatabaseWhenBothDbSystemAttributesPresent(OpenTelemetrySdk sdk,
                                                                          InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("test");

        // when
        Span span = tracer.spanBuilder("SELECT orders").setSpanKind(SpanKind.CLIENT).startSpan();
        span.setAttribute("db.system.name", "postgresql");
        span.setAttribute("db.system", "mysql");
        span.end();

        // then
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(PLATFORM_TYPE_KEY)).isEqualTo("database");
    }

    @Test
    void doesNotOverridePlatformTypeWhenSetExplicitlyByApplication(OpenTelemetrySdk sdk,
                                                                   InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("test");

        // when
        Span span = tracer.spanBuilder("custom-op").setSpanKind(SpanKind.CLIENT).startSpan();
        span.setAttribute(PlatformAttributes.PLATFORM_TYPE, "rpc");
        span.setAttribute("db.system.name", "postgresql");
        span.end();

        // then
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(PLATFORM_TYPE_KEY)).isEqualTo("rpc");
    }

    @Test
    void doesNotOverridePlatformTypeForServerSpanWithDbAttributes(OpenTelemetrySdk sdk,
                                                                  InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("test");

        // when
        Span span = tracer.spanBuilder("server-op").setSpanKind(SpanKind.SERVER).startSpan();
        span.setAttribute("db.system.name", "postgresql");
        span.end();

        // then
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(PLATFORM_TYPE_KEY)).isEqualTo("http_server");
    }

    @Test
    void neverProjectsReservedPlatformAttributesFromBaggage(OpenTelemetrySdk sdk,
                                                            InMemorySpanExporter exporter) {
        // given
        Baggage baggage = Baggage.builder()
                .put(PlatformAttributes.PLATFORM_REQUEST_ID, "req-123")
                .put("platform.policy.version", "v2")
                .build();
        Context context = Context.current().with(baggage);
        Tracer tracer = sdk.getTracer("test");

        // when
        try (Scope ignored = context.makeCurrent()) {
            Span span = tracer.spanBuilder("test-op").startSpan();
            span.end();
        }

        // then
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_REQUEST_ID))).isNull();
        assertThat(data.getAttributes().get(AttributeKey.stringKey("platform.policy.version"))).isNull();
    }
}
