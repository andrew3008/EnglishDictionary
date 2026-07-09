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
 * PR-2b: v3 runtime проставляет {@code PLATFORM_SPAN_CATEGORY} — typed enrichment работает.
 */
class V3MarkerPortCharacterizationTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private DefaultPlatformTracing tracing;
    private SpanEnricher enricher;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        tracing = new DefaultPlatformTracing(
                OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build());
        enricher = new SpanEnricher(new AttributePolicy());
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
    }

    @Test
    void v3ManualSpan_триггеритTypedEnrichment_дляСовпадающейКатегории() {
        try (var ignored = tracing.manual().operation("v3-op").start()) {
            enricher.enrichCurrentSpanIfPlatformCategory(SpanCategory.INTERNAL, scope -> scope
                    .attribute(SemconvKeys.PLATFORM_REQUEST_ID, "r-v3"));
        }

        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(SemconvKeys.PLATFORM_REQUEST_ID)).isEqualTo("r-v3");
    }

    @Test
    void v3ManualSpan_неОбогащает_приНесовпадающейКатегории() {
        try (var ignored = tracing.manual().operation("v3-op").start()) {
            enricher.enrichCurrentSpanIfPlatformCategory(SpanCategory.HTTP_SERVER, scope -> scope
                    .attribute(SemconvKeys.HTTP_ROUTE, "/secret"));
        }

        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(SemconvKeys.HTTP_ROUTE)).isNull();
    }

    @Test
    void v3RootSpan_сохраняетTopology_иМаркерДляEnrich() {
        try (var ignored = tracing.manual().operation("root-op").root().start()) {
            enricher.enrichCurrentSpanIfPlatformCategory(SpanCategory.INTERNAL, scope -> scope
                    .attribute(SemconvKeys.PLATFORM_REQUEST_ID, "root-req"));
        }

        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getName()).isEqualTo("root-op");
        assertThat(data.getParentSpanId()).isEqualTo("0000000000000000");
        assertThat(data.getAttributes().get(SemconvKeys.PLATFORM_REQUEST_ID)).isEqualTo("root-req");
    }
}
