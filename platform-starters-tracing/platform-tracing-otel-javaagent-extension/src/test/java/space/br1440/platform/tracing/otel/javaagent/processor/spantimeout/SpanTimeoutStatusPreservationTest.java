package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

class SpanTimeoutStatusPreservationTest {

    static Stream<Arguments> applicationStatuses() {
        return Stream.of(
                Arguments.of(StatusCode.UNSET, ""),
                Arguments.of(StatusCode.ERROR, "app failure"),
                Arguments.of(StatusCode.OK, ""));
    }

    @ParameterizedTest
    @MethodSource("applicationStatuses")
    void forceEndPreservesTheApplicationStatusAndOnlyAddsTimeoutMarkers(StatusCode statusCode, String description) {
        // given
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        AtomicLong clock = new AtomicLong();
        SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofMillis(10), Duration.ofMinutes(1), clock::get, 10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanTimeout)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()) {
            Tracer tracer = sdk.getTracer("status");
            Span span = tracer.spanBuilder("hung").startSpan();
            if (statusCode != StatusCode.UNSET) {
                span.setStatus(statusCode, description);
            }
            clock.set(Duration.ofMillis(11).toNanos());

            // when
            assertThat(spanTimeout.forceFlush().isSuccess()).isTrue();

            // then
            assertThat(exporter.getFinishedSpanItems()).hasSize(1);
            SpanData data = exporter.getFinishedSpanItems().getFirst();
            assertThat(data.getStatus().getStatusCode()).isEqualTo(statusCode);
            assertThat(data.getStatus().getDescription()).isEqualTo(description);
            assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TIMEOUT)))
                    .isEqualTo("span");
            assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT)))
                    .isEqualTo("timeout");
            assertThat(spanTimeout.getForcedSpanEndAttemptsTotal()).isEqualTo(1);

            span.end();
            spanTimeout.shutdown();
            tracerProvider.shutdown();
        }
    }
}
