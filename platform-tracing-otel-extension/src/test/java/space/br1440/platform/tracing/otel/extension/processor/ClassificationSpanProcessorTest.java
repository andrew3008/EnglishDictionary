package space.br1440.platform.tracing.otel.extension.processor;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

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
        Instant start = Instant.now();
        Instant end = start.plusMillis(500); // 500ms is < 1s (normal threshold)

        tracer.spanBuilder("fast-op")
                .setStartTimestamp(start)
                .startSpan()
                .end(end);

        SpanData spanData = exporter.getFinishedSpanItems().get(0);
        assertThat(spanData.getAttributes().get(DURATION_CLASS_KEY)).isEqualTo("fast");
        assertThat(spanData.getAttributes().get(PRIORITY_KEY)).isEqualTo("normal");
    }

    @Test
    void classifiesNormalSpan() {
        Instant start = Instant.now();
        Instant end = start.plusMillis(2000); // 2s is >= 1s and < 5s

        tracer.spanBuilder("normal-op")
                .setStartTimestamp(start)
                .startSpan()
                .end(end);

        SpanData spanData = exporter.getFinishedSpanItems().get(0);
        assertThat(spanData.getAttributes().get(DURATION_CLASS_KEY)).isEqualTo("normal");
        assertThat(spanData.getAttributes().get(PRIORITY_KEY)).isEqualTo("normal");
    }

    @Test
    void classifiesSlowSpan() {
        Instant start = Instant.now();
        Instant end = start.plusMillis(6000); // 6s is > 5s (slow threshold)

        tracer.spanBuilder("slow-op")
                .setStartTimestamp(start)
                .startSpan()
                .end(end);

        SpanData spanData = exporter.getFinishedSpanItems().get(0);
        assertThat(spanData.getAttributes().get(DURATION_CLASS_KEY)).isEqualTo("slow");
        assertThat(spanData.getAttributes().get(PRIORITY_KEY)).isEqualTo("high");
    }

    @Test
    void setsHighPriorityForErrorSpansEvenIfFast() {
        Instant start = Instant.now();
        Instant end = start.plusMillis(100);

        io.opentelemetry.api.trace.Span span = tracer.spanBuilder("error-op")
                .setStartTimestamp(start)
                .startSpan();
        span.setStatus(StatusCode.ERROR);
        span.end(end);

        SpanData spanData = exporter.getFinishedSpanItems().get(0);
        assertThat(spanData.getAttributes().get(DURATION_CLASS_KEY)).isEqualTo("fast");
        assertThat(spanData.getAttributes().get(PRIORITY_KEY)).isEqualTo("high");
    }
}
