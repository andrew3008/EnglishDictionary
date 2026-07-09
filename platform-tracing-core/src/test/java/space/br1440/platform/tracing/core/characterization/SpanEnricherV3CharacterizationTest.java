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
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.core.facade.DefaultPlatformTracing;
import space.br1440.platform.tracing.core.enrichment.SpanEnricher;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-0: параллельные тесты {@link SpanEnricher} через v3 {@code PlatformTracing.manual()},
 * без прямой зависимости от {@code InternalSpanBuilderImpl}.
 */
class SpanEnricherV3CharacterizationTest {

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
    void enrichCurrentSpan_наV3ManualSpan_пишетPlatformSafeАтрибуты() {
        try (var ignored = tracing.manual().operation("checkout").start()) {
            enricher.enrichCurrentSpan(scope -> scope
                    .requestId("req-v3")
                    .userHash("hash-v3")
                    .result(SpanResult.SUCCESS));
        }

        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(SemconvKeys.PLATFORM_REQUEST_ID)).isEqualTo("req-v3");
        assertThat(data.getAttributes().get(SemconvKeys.PLATFORM_USER_HASH)).isEqualTo("hash-v3");
        assertThat(data.getAttributes().get(SemconvKeys.PLATFORM_RESULT)).isEqualTo("success");
    }

    @Test
    void enrichCurrentSpan_внеАктивногоSpan_noOp() {
        enricher.enrichCurrentSpan(scope -> scope.requestId("orphan"));
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }
}
