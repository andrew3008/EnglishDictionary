package space.br1440.platform.tracing.core.span;

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
import space.br1440.platform.tracing.api.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.core.enrichment.SpanEnricher;
import space.br1440.platform.tracing.core.facade.DefaultPlatformTracing;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;

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
        enricher = new SpanEnricher(new AttributePolicy());
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
    }

    @Test
    void enrichCurrentSpan_пишетТолькоPlatformSafeАтрибуты_иНормализуетBusinessTag() {
        Tracer tracer = sdk.getTracer("test");
        Span span = tracer.spanBuilder("op").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            enricher.enrichCurrentSpan(scope -> scope
                    .requestId("req-1")
                    .userHash("u-hash")
                    .businessTag("Order Type", "premium")
                    .result(SpanResult.SUCCESS));
        } finally {
            span.end();
        }

        SpanData data = exporter.getFinishedSpanItems().get(0);
        assertThat(data.getAttributes().get(SemconvKeys.PLATFORM_REQUEST_ID)).isEqualTo("req-1");
        assertThat(data.getAttributes().get(SemconvKeys.PLATFORM_USER_HASH)).isEqualTo("u-hash");
        assertThat(data.getAttributes().get(SemconvKeys.PLATFORM_RESULT)).isEqualTo("success");
        assertThat(data.getAttributes()
                .get(io.opentelemetry.api.common.AttributeKey.stringKey("platform.business.order_type")))
                .isEqualTo("premium");
    }

    @Test
    void enrichCurrentSpanIfPlatformCategory_сV3Маркером_применяетAllowlistИОтбрасываетChужие() {
        DefaultPlatformTracing tracing = new DefaultPlatformTracing(sdk);
        try (var ignored = tracing.manual().operation("op").start()) {
            enricher.enrichCurrentSpanIfPlatformCategory(SpanCategory.INTERNAL, scope -> scope
                    .attribute(SemconvKeys.PLATFORM_REQUEST_ID, "r1")
                    .attribute(SemconvKeys.HTTP_ROUTE, "/secret/path"));
        }

        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(SemconvKeys.PLATFORM_REQUEST_ID)).isEqualTo("r1");
        assertThat(data.getAttributes().get(SemconvKeys.HTTP_ROUTE)).isNull();
    }

    @Test
    void enrichCurrentSpanIfPlatformCategory_безМаркера_наАгентскомSpan_noOp() {
        Tracer tracer = sdk.getTracer("test");
        Span span = tracer.spanBuilder("agent-span").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            enricher.enrichCurrentSpanIfPlatformCategory(SpanCategory.INTERNAL, scope -> scope
                    .attribute(SemconvKeys.PLATFORM_REQUEST_ID, "r2"));
        } finally {
            span.end();
        }

        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(SemconvKeys.PLATFORM_REQUEST_ID)).isNull();
    }

    @Test
    void enrichCurrentSpan_безАктивногоSpan_noOp() {
        enricher.enrichCurrentSpan(scope -> scope.requestId("x"));
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }
}
