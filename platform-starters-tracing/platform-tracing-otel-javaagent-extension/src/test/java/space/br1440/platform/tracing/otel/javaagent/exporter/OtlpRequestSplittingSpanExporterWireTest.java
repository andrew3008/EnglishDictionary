package space.br1440.platform.tracing.otel.javaagent.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseObservation;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseScope;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ObservingSenderTestFixtures;

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
import io.opentelemetry.sdk.common.export.HttpSender;
import io.opentelemetry.sdk.common.export.HttpSenderConfig;
import io.opentelemetry.sdk.common.export.HttpSenderProvider;
import io.opentelemetry.sdk.common.export.MemoryMode;
import io.opentelemetry.sdk.common.export.MessageWriter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

class OtlpRequestSplittingSpanExporterWireTest {

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

            OtlpRequestSplittingSpanExporter isolator = isolator(delegate, limit);
            ExportResponseObservation rejected = new ExportResponseObservation(1);

            // when
            CompletableResultCode accepted = isolator.export(List.of(exact));
            CompletableResultCode dropped = export(isolator, List.of(larger), rejected);

            // then
            assertThat(accepted.isSuccess()).isTrue();
            assertThat(dropped.isSuccess()).isTrue();
            assertThat(capture.payloads).singleElement().satisfies(bytes -> assertThat(bytes).hasSize(limit));
            assertThat(capture.declaredLengths).containsExactly(limit);
            assertThat(rejected.aggregateResult())
                    .isEqualTo(new ExportResponseObservation.AggregateResult(0, 0, 0, 0, 1));
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
            assertThat(parent.aggregateResult()).isEqualTo(new ExportResponseObservation.AggregateResult(4, 0, 0, 0, 0));
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
            assertThat(parent.aggregateResult()).isEqualTo(new ExportResponseObservation.AggregateResult(0, 2, 0, 0, 0));
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
            assertThat(parent.aggregateResult()).isEqualTo(new ExportResponseObservation.AggregateResult(0, 0, 0, 2, 0));
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
            assertThat(capture.payloads).hasSize(1);
            assertThat(parent.aggregateResult()).isEqualTo(new ExportResponseObservation.AggregateResult(0, 0, 2, 0, 0));
        }
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

    private static OtlpRequestSplittingSpanExporter isolator(SpanExporter delegate, int limit) {
        return new OtlpRequestSplittingSpanExporter(delegate, limit, TimeUnit.SECONDS.toNanos(30), () -> 0L);
    }

    private static SpanExporter exporter(Protocol protocol, MemoryMode mode, SenderCapture capture) {
        return protocol == Protocol.GRPC
                ? OtlpGrpcSpanExporter.builder().setComponentLoader(capture).setMemoryMode(mode).setRetryPolicy(null).build()
                : OtlpHttpSpanExporter.builder().setComponentLoader(capture).setMemoryMode(mode).setRetryPolicy(null).build();
    }

    private static CompletableResultCode export(SpanExporter exporter,
                                                 List<SpanData> spans,
                                                 ExportResponseObservation observation) {
        try (ExportResponseScope.Handle ignored = ExportResponseScope.open(observation)) {
            return exporter.export(spans);
        }
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
