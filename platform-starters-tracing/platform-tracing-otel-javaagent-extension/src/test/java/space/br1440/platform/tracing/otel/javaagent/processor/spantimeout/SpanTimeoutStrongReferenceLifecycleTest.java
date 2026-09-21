package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

class SpanTimeoutStrongReferenceLifecycleTest {

    @Test
    void unendedSpanWithoutApplicationReferencesIsForceEndedAndReachesTheExporter() {
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
            startAndDrop(sdk.getTracer("leak"));
            assertThat(spanTimeout.getActiveSpanCount()).isEqualTo(1);
            clock.set(Duration.ofMillis(11).toNanos());

            // when
            assertThat(spanTimeout.forceFlush().isSuccess()).isTrue();

            // then
            assertThat(exporter.getFinishedSpanItems()).hasSize(1);
            SpanData data = exporter.getFinishedSpanItems().getFirst();
            assertThat(data.getName()).isEqualTo("leaked");
            assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TIMEOUT)))
                    .isEqualTo("span");
            assertThat(spanTimeout.getActiveSpanCount()).isZero();
            assertThat(spanTimeout.getForcedSpanEndAttemptsTotal()).isEqualTo(1);

            spanTimeout.shutdown();
            tracerProvider.shutdown();
        }
    }

    private static void startAndDrop(Tracer tracer) {
        tracer.spanBuilder("leaked").startSpan();
    }
}
