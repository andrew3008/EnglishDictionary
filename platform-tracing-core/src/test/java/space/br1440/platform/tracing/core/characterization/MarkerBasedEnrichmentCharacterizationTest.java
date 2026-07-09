package space.br1440.platform.tracing.core.characterization;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.core.facade.DefaultPlatformTracing;
import space.br1440.platform.tracing.core.enrichment.SpanEnricher;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-2b: marker-based enrichment через v3 runtime ({@code PLATFORM_SPAN_CATEGORY}).
 */
class MarkerBasedEnrichmentCharacterizationTest {

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
        enricher = new SpanEnricher(new AttributePolicy());
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
    }

    @Test
    void v3ManualSpan_сМаркером_триггеритTypedEnrichment() {
        DefaultPlatformTracing tracing = new DefaultPlatformTracing(sdk);
        try (var ignored = tracing.manual().operation("v3-op").start()) {
            enricher.enrichCurrentSpanIfPlatformCategory(SpanCategory.INTERNAL, scope -> scope
                    .attribute(SemconvKeys.PLATFORM_REQUEST_ID, "r-v3"));
        }

        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(SemconvKeys.PLATFORM_REQUEST_ID)).isEqualTo("r-v3");
    }
}
