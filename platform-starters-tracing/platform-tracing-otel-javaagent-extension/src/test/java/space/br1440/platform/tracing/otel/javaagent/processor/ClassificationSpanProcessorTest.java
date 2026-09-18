package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import io.opentelemetry.api.trace.Span;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

class ClassificationSpanProcessorTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private Tracer tracer;

    private static final AttributeKey<String> DURATION_CLASS_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_TRACE_DURATION_CLASS);
    private static final AttributeKey<String> PRIORITY_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_TRACE_PRIORITY);

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        ClassificationSpanProcessor classificationProcessor = new ClassificationSpanProcessor(
                Duration.ofSeconds(5), // slow = 5s
                Duration.ofSeconds(1)  // normal = 1s
        );

        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(classificationProcessor)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .setResource(Resource.empty())
                .build();

        tracer = tracerProvider.get("test-tracer");
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
        exporter.reset();
    }

    @Test
    void classifiesFastSpan() {
        // given
        Instant start = Instant.now();
        Instant end = start.plusMillis(500); // 500ms is < 1s (normal threshold)

        tracer.spanBuilder("fast-op")
                .setStartTimestamp(start)
                // when
                .startSpan()
                .end(end);

        SpanData spanData = exporter.getFinishedSpanItems().getFirst();
        // then
        assertThat(spanData.getAttributes().get(DURATION_CLASS_KEY)).isEqualTo("fast");
        assertThat(spanData.getAttributes().get(PRIORITY_KEY)).isEqualTo("normal");
    }

    @Test
    void classifiesNormalSpan() {
        // given
        Instant start = Instant.now();
        Instant end = start.plusMillis(2000); // 2s is >= 1s and < 5s

        tracer.spanBuilder("normal-op")
                .setStartTimestamp(start)
                // when
                .startSpan()
                .end(end);

        SpanData spanData = exporter.getFinishedSpanItems().getFirst();
        // then
        assertThat(spanData.getAttributes().get(DURATION_CLASS_KEY)).isEqualTo("normal");
        assertThat(spanData.getAttributes().get(PRIORITY_KEY)).isEqualTo("normal");
    }

    @Test
    void classifiesSlowSpan() {
        // given
        Instant start = Instant.now();
        Instant end = start.plusMillis(6000); // 6s is > 5s (slow threshold)

        tracer.spanBuilder("slow-op")
                .setStartTimestamp(start)
                // when
                .startSpan()
                .end(end);

        SpanData spanData = exporter.getFinishedSpanItems().getFirst();
        // then
        assertThat(spanData.getAttributes().get(DURATION_CLASS_KEY)).isEqualTo("slow");
        assertThat(spanData.getAttributes().get(PRIORITY_KEY)).isEqualTo("high");
    }

    @Test
    void setsHighPriorityForErrorSpansEvenIfFast() {
        // given
        Instant start = Instant.now();
        Instant end = start.plusMillis(100);

        Span span = tracer.spanBuilder("error-op")
                .setStartTimestamp(start)
                // when
                .startSpan();
        span.setStatus(StatusCode.ERROR);
        span.end(end);

        SpanData spanData = exporter.getFinishedSpanItems().getFirst();
        // then
        assertThat(spanData.getAttributes().get(DURATION_CLASS_KEY)).isEqualTo("fast");
        assertThat(spanData.getAttributes().get(PRIORITY_KEY)).isEqualTo("high");
    }
}
