package space.br1440.platform.tracing.otel.javaagent.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.otel.javaagent.configuration.OtelSdkDefaults;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseObservation;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseScope;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.common.ComponentLoader;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.GrpcResponse;
import io.opentelemetry.sdk.common.export.GrpcSender;
import io.opentelemetry.sdk.common.export.GrpcSenderProvider;
import io.opentelemetry.sdk.common.export.HttpResponse;
import io.opentelemetry.sdk.common.export.HttpSender;
import io.opentelemetry.sdk.common.export.HttpSenderProvider;
import io.opentelemetry.sdk.common.export.MemoryMode;
import io.opentelemetry.sdk.common.export.MessageWriter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanLimits;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

@Timeout(value = 120, unit = TimeUnit.SECONDS)
class OtlpRequestSplittingSpanExporterOverflowTest {

    private static final int LIMIT = Math.toIntExact(OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES);

    @ParameterizedTest
    @MethodSource("bareSdkCases")
    void bareSdkExposesWrappedSizeBeforeSender(boolean grpc, int count) {
        // given
        List<SpanData> spans = Collections.nCopies(count, oversizedSpan());
        HeaderCapture capture = new HeaderCapture();
        try (SpanExporter delegate = exporter(grpc, MemoryMode.REUSABLE_DATA, capture)) {
            // when
            CompletableResultCode result = delegate.export(spans);

            // then
            assertThat(result.isSuccess()).isFalse();
            if (count == 224) {
                assertThat(capture.calls).isEqualTo(1);
                assertThat(capture.contentLength).isPositive().isLessThan(LIMIT);
            } else if (grpc) {
                assertThat(capture.calls).isEqualTo(1);
                assertThat(capture.contentLength).isNegative();
            } else {
                assertThat(capture.calls).isZero();
            }
            System.out.printf("SDK overflow: protocol=%s, spans=%d, senderCalls=%d, contentLength=%d%n",
                    grpc ? "grpc" : "http", count, capture.calls, capture.contentLength);
        }
    }

    @ParameterizedTest
    @MethodSource("isolatedCases")
    void isolatorDropsPositiveWrapBeforeEitherMemoryModeReachesSender(boolean grpc, MemoryMode memoryMode) {
        // given
        List<SpanData> spans = Collections.nCopies(224, oversizedSpan());
        HeaderCapture capture = new HeaderCapture();
        ExportResponseObservation observation = new ExportResponseObservation(spans.size());
        try (SpanExporter delegate = exporter(grpc, memoryMode, capture);
             ExportResponseScope.Handle ignored = ExportResponseScope.open(observation)) {
            SpanExporter isolator = new OtlpRequestSplittingSpanExporter(delegate, LIMIT, 1, () -> 0L);

            // when
            CompletableResultCode result = isolator.export(spans);

            // then
            assertThat(result.isSuccess()).as("isolation failure: %s", result.getFailureThrowable()).isTrue();
            assertThat(capture.calls).isZero();
            assertThat(observation.aggregateResult())
                    .isEqualTo(new ExportResponseObservation.AggregateResult(0, 0, 0, 0, spans.size()));
        }
    }

    private static SpanData oversizedSpan() {
        List<String> values = Collections.nCopies(128, "漢".repeat(1000));
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .setResource(Resource.empty())
                .setSpanLimits(SpanLimits.builder().setMaxNumberOfAttributes(50).setMaxAttributeValueLength(1000).build())
                .addSpanProcessor(SimpleSpanProcessor.create(SpanExporter.composite()))
                .build()) {
            Span span = provider.get("overflow").spanBuilder("overflow").setNoParent().startSpan();
            for (int index = 0; index < 50; index++) {
                span.setAttribute(AttributeKey.stringArrayKey("array" + index), values);
            }
            span.end();
            SpanData data = ((ReadableSpan) span).toSpanData();
            assertThat(data.getAttributes().size()).isEqualTo(50);
            assertThat(data.getAttributes().get(AttributeKey.stringArrayKey("array0"))).hasSize(128);
            return data;
        }
    }

    private static SpanExporter exporter(boolean grpc, MemoryMode memoryMode, HeaderCapture capture) {
        return grpc
                ? OtlpGrpcSpanExporter.builder().setMemoryMode(memoryMode).setComponentLoader(capture)
                .setRetryPolicy(null).setMaxRequestMessageSize(LIMIT).build()
                : OtlpHttpSpanExporter.builder().setMemoryMode(memoryMode).setComponentLoader(capture)
                .setRetryPolicy(null).setMaxRequestBodySize(LIMIT).build();
    }

    private static Stream<Arguments> bareSdkCases() {
        return Stream.of(Arguments.of(true, 224), Arguments.of(false, 224),
                Arguments.of(true, 128), Arguments.of(false, 128));
    }

    private static Stream<Arguments> isolatedCases() {
        return Stream.of(true, false).flatMap(grpc -> Stream.of(MemoryMode.values())
                .map(mode -> Arguments.of(grpc, mode)));
    }

    private static final class HeaderCapture implements ComponentLoader {

        private int calls;
        private int contentLength;

        @Override
        public <T> List<T> load(Class<T> spiClass) {
            if (spiClass == GrpcSenderProvider.class) {
                GrpcSenderProvider provider = config -> new GrpcSender() {
                    @Override
                    public void send(MessageWriter writer, Consumer<GrpcResponse> response, Consumer<Throwable> failure) {
                        capture(writer, failure);
                    }

                    @Override
                    public CompletableResultCode shutdown() {
                        return CompletableResultCode.ofSuccess();
                    }
                };
                return List.of(spiClass.cast(provider));
            }
            if (spiClass == HttpSenderProvider.class) {
                HttpSenderProvider provider = config -> new HttpSender() {
                    @Override
                    public void send(MessageWriter writer, Consumer<HttpResponse> response, Consumer<Throwable> failure) {
                        capture(writer, failure);
                    }

                    @Override
                    public CompletableResultCode shutdown() {
                        return CompletableResultCode.ofSuccess();
                    }
                };
                return List.of(spiClass.cast(provider));
            }
            return List.of();
        }

        private void capture(MessageWriter writer, Consumer<Throwable> failure) {
            calls++;
            contentLength = writer.getContentLength();
            failure.accept(new IOException("Oversized request captured before transmission"));
        }
    }
}
