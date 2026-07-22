package space.br1440.platform.tracing.otel.span;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.api.span.enrich.SpanEnricher;
import space.br1440.platform.tracing.otel.enrichment.DefaultSpanEnricher;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;

import static org.assertj.core.api.Assertions.assertThat;

class SpanEnricherTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private OpenTelemetrySdk sdk;
    private SpanEnricher enricher;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
        enricher = new DefaultSpanEnricher();
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
    }

    @Test
    void enrichCurrentSpan_пишетТолькоPlatformSafeАтрибуты() {
        Tracer tracer = sdk.getTracer("test");
        Span span = tracer.spanBuilder("op").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            enricher.enrichCurrentSpan(scope -> scope
                    .requestId("req-1")
                    .userHash("u-hash")
                    .result(SpanResult.SUCCESS));
        } finally {
            span.end();
        }

        SpanData data = exporter.getFinishedSpanItems().get(0);
        assertThat(data.getAttributes().get(SemconvKeys.PLATFORM_REQUEST_ID)).isEqualTo("req-1");
        assertThat(data.getAttributes().get(SemconvKeys.PLATFORM_USER_HASH)).isEqualTo("u-hash");
        assertThat(data.getAttributes().get(SemconvKeys.PLATFORM_RESULT)).isEqualTo("success");
    }

    @Test
    void enrichCurrentSpan_безАктивногоSpan_noOp() {
        enricher.enrichCurrentSpan(scope -> scope.requestId("x"));
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }
}
