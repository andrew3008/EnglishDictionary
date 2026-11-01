package space.br1440.platform.tracing.otel.javaagent.exporter.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseObservation;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseScope;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ObservingSenderTestFixtures;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.common.ComponentLoader;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.proto.collector.trace.v1.ExportTracePartialSuccess;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.GrpcResponse;
import io.opentelemetry.sdk.common.export.GrpcSender;
import io.opentelemetry.sdk.common.export.GrpcSenderConfig;
import io.opentelemetry.sdk.common.export.GrpcSenderProvider;
import io.opentelemetry.sdk.common.export.GrpcStatusCode;
import io.opentelemetry.sdk.common.export.HttpResponse;
import io.opentelemetry.exporter.sender.okhttp.internal.OkHttpHttpSenderProvider;
import io.opentelemetry.sdk.common.export.HttpSender;
import io.opentelemetry.sdk.common.export.HttpSenderConfig;
import io.opentelemetry.sdk.common.export.HttpSenderProvider;
import io.opentelemetry.sdk.common.export.RetryPolicy;
import io.opentelemetry.sdk.common.export.MemoryMode;
import io.opentelemetry.sdk.common.export.MessageWriter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okio.Buffer;

class OtlpRequestSizeLimitingSpanExporterWireTest {

    @Test
    void spanHttpExporterBuilderHasNoJsonSerializationMode() {
        assertThat(Stream.of(OtlpHttpSpanExporterBuilder.class.getDeclaredMethods()).map(Method::getName))
                .as("бинарный замер перестаёт соответствовать проверке HttpExporter, если у spans появляется JSON")
                .doesNotContain("exportAsJson");
    }

    @ParameterizedTest
    @MethodSource("protocolsAndMemoryModes")
    void exactSerializedLimitPassesAndOneExtraByteIsDropped(Protocol protocol, MemoryMode memoryMode) {
        // given
        SenderCapture capture = new SenderCapture();
        try (SdkTracerProvider provider = provider();
             SpanExporter delegate = exporter(protocol, memoryMode, capture)) {
            SpanData exact = span(provider, "scope", "漢é漢".repeat(50));
            SpanData larger = new DelegatingSpanData(exact) {
                @Override
                public String getName() {
                    return super.getName() + "b";
                }
            };

            delegate.export(List.of(exact));

            int limit = capture.payloads.getFirst().length;
            delegate.export(List.of(larger));
            assertThat(capture.payloads.getLast()).hasSize(limit + 1);
            capture.payloads.clear();
            capture.declaredLengths.clear();

            OtlpRequestSizeLimitingSpanExporter isolator = isolator(delegate, limit);
            ExportResponseObservation rejected = new ExportResponseObservation(1);

            // when
            CompletableResultCode accepted = isolator.export(List.of(exact), new ExportResponseObservation(1));
            CompletableResultCode dropped = export(isolator, List.of(larger), rejected);

            // then
            assertThat(accepted.isSuccess()).isTrue();
            assertThat(dropped.isSuccess()).isTrue();
            assertThat(capture.payloads).singleElement().satisfies(bytes -> assertThat(bytes).hasSize(limit));
            assertThat(capture.declaredLengths).containsExactly(limit);
            assertThat(rejected.aggregateResult())
                    .isEqualTo(new ExportResponseObservation.AggregateResult(0, 0, 0, 0, 1, 0, 0, null));
        }
    }

    @ParameterizedTest
    @MethodSource("protocolsAndMemoryModes")
    void serializedLeavesPreserveMixedResourcesScopesEventsAndLinks(Protocol protocol, MemoryMode memoryMode) throws IOException {
        // given
        SenderCapture capture = new SenderCapture();
        try (SdkTracerProvider first = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(SpanExporter.composite()))
                .setResource(Resource.create(Attributes.builder().put("service.name", "first").build())).build();
             SdkTracerProvider second = SdkTracerProvider.builder()
                     .addSpanProcessor(SimpleSpanProcessor.create(SpanExporter.composite()))
                     .setResource(Resource.create(Attributes.builder().put("service.name", "second").build())).build();
             SpanExporter delegate = exporter(protocol, memoryMode, capture)) {

            List<SpanData> spans = List.of(
                    span(first, "scope-a", "a".repeat(300)), span(first, "scope-b", "b".repeat(300)),
                    span(second, "scope-a", "c".repeat(300)), span(second, "scope-b", "d".repeat(300)));

            for (SpanData span : spans) {
                delegate.export(List.of(span));
            }

            int limit = capture.payloads.stream().mapToInt(bytes -> bytes.length).max().orElseThrow();
            capture.payloads.clear();
            capture.declaredLengths.clear();
            ExportResponseObservation parent = new ExportResponseObservation(spans.size());

            // when
            CompletableResultCode result = export(isolator(delegate, limit), spans, parent);

            // then
            assertThat(result.isSuccess()).isTrue();
            assertThat(parent.aggregateResult()).isEqualTo(new ExportResponseObservation.AggregateResult(4, 0, 0, 0, 0, 0, 4, null));
            assertThat(capture.payloads).hasSize(4).allSatisfy(bytes -> assertThat(bytes.length).isLessThanOrEqualTo(limit));
            List<String> ids = new ArrayList<>();
            List<String> resources = new ArrayList<>();
            List<String> scopes = new ArrayList<>();
            for (int index = 0; index < capture.payloads.size(); index++) {
                byte[] payload = capture.payloads.get(index);
                assertThat(capture.declaredLengths.get(index)).isEqualTo(payload.length);
                ExportTraceServiceRequest request = ExportTraceServiceRequest.parseFrom(payload);
                request.getResourceSpansList().forEach(resource -> {
                    resources.add(resource.getResource().getAttributes(0).getValue().getStringValue());
                    resource.getScopeSpansList().forEach(scope -> {
                        scopes.add(scope.getScope().getName());
                        scope.getSpansList().forEach(span -> {
                            ids.add(HexFormat.of().formatHex(span.getSpanId().toByteArray()));
                            assertThat(span.getEventsCount()).isEqualTo(1);
                            assertThat(span.getLinksCount()).isEqualTo(1);
                            assertThat(span.getAttributes(0).getValue().getStringValue()).isEqualTo("漢é漢");
                            assertThat(span.getStatus().getMessage()).isEqualTo("test error");
                        });
                    });
                });
            }

            assertThat(ids).containsExactlyElementsOf(spans.stream().map(span -> span.getSpanContext().getSpanId()).toList());
            assertThat(resources).containsExactly("first", "first", "second", "second");
            assertThat(scopes).containsExactly("scope-a", "scope-b", "scope-a", "scope-b");
        }
    }

    @ParameterizedTest
    @MethodSource("protocolsAndMemoryModes")
    void partialResponsesAreCountedWithoutRetry(Protocol protocol, MemoryMode memoryMode) {
        // given
        SenderCapture capture = new SenderCapture();
        capture.body = ExportTraceServiceResponse.newBuilder()
                .setPartialSuccess(ExportTracePartialSuccess.newBuilder().setRejectedSpans(1)).build().toByteArray();

        try (SdkTracerProvider provider = provider();
             SpanExporter delegate = exporter(protocol, memoryMode, capture)) {
            List<SpanData> spans = List.of(
                    span(provider, "scope", "a".repeat(800)),
                    span(provider, "scope", "b".repeat(800))
            );
            ExportResponseObservation parent = new ExportResponseObservation(2);

            // when
            CompletableResultCode result = export(isolator(delegate, 1200), spans, parent);

            // then
            assertThat(result.isSuccess()).isTrue();
            assertThat(capture.payloads).hasSize(2);
            assertThat(parent.aggregateResult()).isEqualTo(new ExportResponseObservation.AggregateResult(0, 2, 0, 0, 0, 0, 2, null));
        }
    }

    @ParameterizedTest
    @MethodSource("protocolsAndMemoryModes")
    void unreadableSuccessResponsesRemainUndetermined(Protocol protocol, MemoryMode memoryMode) {
        // given
        SenderCapture capture = new SenderCapture();
        capture.body = new byte[]{(byte) 0xff};
        try (SdkTracerProvider provider = provider();
             SpanExporter delegate = exporter(protocol, memoryMode, capture)) {
            List<SpanData> spans = List.of(
                    span(provider, "scope", "a".repeat(800)),
                    span(provider, "scope", "b".repeat(800))
            );
            ExportResponseObservation parent = new ExportResponseObservation(2);

            // when
            CompletableResultCode result = export(isolator(delegate, 1200), spans, parent);

            // then
            assertThat(result.isSuccess()).isTrue();
            assertThat(capture.payloads).hasSize(2);
            assertThat(parent.aggregateResult()).isEqualTo(new ExportResponseObservation.AggregateResult(0, 0, 0, 2, 0, 0, 2, null));
        }
    }

    @ParameterizedTest
    @MethodSource("protocolsAndMemoryModes")
    void failedResponseBodyDoesNotCountAsAcceptance(Protocol protocol, MemoryMode memoryMode) {
        // given
        SenderCapture capture = new SenderCapture();
        capture.httpStatus = 400;
        capture.grpcStatus = GrpcStatusCode.INVALID_ARGUMENT;
        capture.body = ExportTraceServiceResponse.newBuilder()
                .setPartialSuccess(ExportTracePartialSuccess.newBuilder().setRejectedSpans(1)).build().toByteArray();
        try (SdkTracerProvider provider = provider();
             SpanExporter delegate = exporter(protocol, memoryMode, capture)) {
            List<SpanData> spans = List.of(span(provider, "scope", "a".repeat(800)),
                    span(provider, "scope", "b".repeat(800)));
            ExportResponseObservation parent = new ExportResponseObservation(2);

            // when
            CompletableResultCode result = export(isolator(delegate, 1200), spans, parent);

            // then
            assertThat(result.isSuccess()).isFalse();
            assertThat(capture.payloads).hasSize(2);
            assertThat(parent.aggregateResult()).isEqualTo(new ExportResponseObservation.AggregateResult(0, 0, 2, 0, 0, 0, 2, null));
        }
    }

    @ParameterizedTest
    @MethodSource("protocolsAndMemoryModes")
    void jointRequestNeverExceedsSumOfPerSpanSizes(Protocol protocol, MemoryMode memoryMode) {
        // given
        SenderCapture capture = new SenderCapture();
        try (SpanExporter delegate = exporter(protocol, memoryMode, capture)) {
            for (int boundary : new int[]{127, 128, 16383, 16384}) {
                String value = "x".repeat(boundary);
                try (SdkTracerProvider first = SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(SpanExporter.composite()))
                        .setResource(Resource.create(Attributes.of(AttributeKey.stringKey("resource"), value)))
                        .build();
                     SdkTracerProvider second = SdkTracerProvider.builder()
                             .addSpanProcessor(SimpleSpanProcessor.create(SpanExporter.composite()))
                             .setResource(Resource.create(Attributes.of(AttributeKey.stringKey("resource"), "other" + value)))
                             .build()) {

                    List<SpanData> spans = List.of(span(first, value, value), span(first, value, value),
                            span(first, "other" + value, value), span(second, value, value));
                    capture.payloads.clear();
                    for (SpanData span : spans) {
                        assertThat(delegate.export(List.of(span)).isSuccess()).isTrue();
                    }

                    long sum = capture.payloads.stream().mapToLong(payload -> payload.length).sum();
                    capture.payloads.clear();
                    ExportResponseObservation parent = new ExportResponseObservation(spans.size());

                    // when
                    CompletableResultCode result = export(isolator(delegate, Math.toIntExact(sum)), spans, parent);

                    // then
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(capture.payloads).singleElement()
                            .satisfies(payload -> assertThat((long) payload.length).isLessThanOrEqualTo(sum));
                    assertThat(parent.aggregateResult()).isEqualTo(
                            new ExportResponseObservation.AggregateResult(4, 0, 0, 0, 0, 0, 1, null));
                }
            }
        }
    }

    @Test
    void httpRetryDoesNotReadTheExportScopeAgain() throws Exception {
        // given
        AtomicInteger sends = new AtomicInteger();
        AtomicReference<ExportResponseObservation> leaf = new AtomicReference<>();
        byte[] body = ExportTraceServiceResponse.getDefaultInstance().toByteArray();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(503).build());
            server.enqueue(new MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "application/x-protobuf")
                    .body(new Buffer().write(body))
                    .build());
            server.start();
            ComponentLoader loader = new ComponentLoader() {
                private final ComponentLoader delegate = ComponentLoader.forClassLoader(getClass().getClassLoader());

                @Override
                public <T> Iterable<T> load(Class<T> spiClass) {
                    if (spiClass == HttpSenderProvider.class) {
                        HttpSenderProvider observing = ObservingSenderTestFixtures.http(config -> countingSender(config, sends, leaf));
                        return List.of(spiClass.cast(observing));
                    }
                    return delegate.load(spiClass);
                }
            };
            try (SdkTracerProvider provider = provider();
                 SpanExporter http = OtlpHttpSpanExporter.builder()
                         .setEndpoint(server.url("/v1/traces").toString())
                         .setComponentLoader(loader)
                         .setTimeout(Duration.ofSeconds(5))
                         .setRetryPolicy(RetryPolicy.builder()
                                 .setMaxAttempts(2)
                                 .setInitialBackoff(Duration.ofMillis(1))
                                 .setMaxBackoff(Duration.ofMillis(1))
                                 .build())
                         .build()) {
                OtlpRequestSizeLimitingSpanExporter guard = isolator(http, 1_000_000);
                ExportResponseObservation parent = new ExportResponseObservation(1);

                // when
                CompletableResultCode result = guard.export(List.of(span(provider, "retry", "leaf")), parent);
                result.join(5, TimeUnit.SECONDS);

                // then
                assertThat(server.getRequestCount()).isEqualTo(2);
                assertThat(sends).hasValue(1);
                assertThat(leaf).hasValueSatisfying(observation -> assertThat(observation.outcome())
                        .isEqualTo(ExportResponseObservation.Outcome.ALL_ACCEPTED));
                assertThat(parent.aggregateResult().accepted()).isEqualTo(1);
            }
        }
    }

    private static HttpSender countingSender(HttpSenderConfig config,
                                             AtomicInteger sends,
                                             AtomicReference<ExportResponseObservation> leaf) {
        HttpSender delegate = new OkHttpHttpSenderProvider().createSender(config);
        return new HttpSender() {
            @Override
            public void send(MessageWriter writer, Consumer<HttpResponse> onResponse, Consumer<Throwable> onError) {
                sends.incrementAndGet();
                leaf.compareAndSet(null, ExportResponseScope.current());
                delegate.send(writer, onResponse, onError);
            }

            @Override
            public CompletableResultCode shutdown() {
                return delegate.shutdown();
            }
        };
    }

    private static Stream<Arguments> protocolsAndMemoryModes() {
        return Stream.of(Protocol.values()).flatMap(protocol -> Stream.of(MemoryMode.values())
                .map(mode -> Arguments.of(protocol, mode)));
    }

    private static SdkTracerProvider provider() {
        return SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(SpanExporter.composite())).build();
    }

    private static SpanData span(SdkTracerProvider provider, String scope, String name) {
        Span linkedSpan = provider.get("linked").spanBuilder("link").startSpan();
        SpanContext linked = linkedSpan.getSpanContext();
        linkedSpan.end();
        Span span = provider.get(scope).spanBuilder(name).addLink(linked).startSpan();
        span.setAttribute("text", "漢é漢");
        span.addEvent("event", Attributes.builder().put("event.key", "value").build());
        span.setStatus(StatusCode.ERROR, "test error");
        span.end();
        return ((ReadableSpan) span).toSpanData();
    }

    private static OtlpRequestSizeLimitingSpanExporter isolator(SpanExporter delegate, int limit) {
        return new OtlpRequestSizeLimitingSpanExporter(delegate, limit);
    }

    private static SpanExporter exporter(Protocol protocol, MemoryMode mode, SenderCapture capture) {
        return protocol == Protocol.GRPC
                ? OtlpGrpcSpanExporter.builder().setComponentLoader(capture).setMemoryMode(mode).setRetryPolicy(null).build()
                : OtlpHttpSpanExporter.builder().setComponentLoader(capture).setMemoryMode(mode).setRetryPolicy(null).build();
    }

    private static CompletableResultCode export(OtlpRequestSizeLimitingSpanExporter exporter,
                                                 List<SpanData> spans,
                                                 ExportResponseObservation observation) {
        return exporter.export(spans, observation);
    }

    private enum Protocol {
        GRPC, HTTP
    }

    private static final class SenderCapture implements ComponentLoader {

        private final ComponentLoader delegate = ComponentLoader.forClassLoader(getClass().getClassLoader());
        private final List<byte[]> payloads = new ArrayList<>();
        private final List<Integer> declaredLengths = new ArrayList<>();
        private byte[] body = new byte[0];
        private int httpStatus = 200;
        private GrpcStatusCode grpcStatus = GrpcStatusCode.OK;

        @Override
        public <T> Iterable<T> load(Class<T> spiClass) {
            if (spiClass == GrpcSenderProvider.class) {
                return List.of(spiClass.cast(ObservingSenderTestFixtures.grpc(this::grpcSender)));
            }
            if (spiClass == HttpSenderProvider.class) {
                return List.of(spiClass.cast(ObservingSenderTestFixtures.http(this::httpSender)));
            }
            return delegate.load(spiClass);
        }

        private void capture(MessageWriter writer) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            writer.writeMessage(output);
            payloads.add(output.toByteArray());
            declaredLengths.add(writer.getContentLength());
        }

        private GrpcSender grpcSender(GrpcSenderConfig config) {
            return new GrpcSender() {
                @Override
                public void send(MessageWriter writer, Consumer<GrpcResponse> onResponse, Consumer<Throwable> onError) {
                    try {
                        capture(writer);
                    } catch (IOException failure) {
                        onError.accept(failure);
                        return;
                    }

                    onResponse.accept(new GrpcResponse() {
                        @Override public GrpcStatusCode getStatusCode() { return grpcStatus; }
                        @Override public String getStatusDescription() { return "test response"; }
                        @Override public byte[] getResponseMessage() { return body; }
                    });
                }

                @Override
                public CompletableResultCode shutdown() {
                    return CompletableResultCode.ofSuccess();
                }
            };
        }

        private HttpSender httpSender(HttpSenderConfig config) {
            return new HttpSender() {
                @Override
                public void send(MessageWriter writer, Consumer<HttpResponse> onResponse, Consumer<Throwable> onError) {
                    try {
                        capture(writer);
                    } catch (IOException failure) {
                        onError.accept(failure);
                        return;
                    }

                    onResponse.accept(new HttpResponse() {
                        @Override public int getStatusCode() { return httpStatus; }
                        @Override public String getStatusMessage() { return "test response"; }
                        @Override public byte[] getResponseBody() { return body; }
                    });
                }

                @Override
                public CompletableResultCode shutdown() {
                    return CompletableResultCode.ofSuccess();
                }
            };
        }
    }
}
