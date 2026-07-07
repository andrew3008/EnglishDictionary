package space.br1440.platform.tracing.core;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 0A baseline characterization tests migrated to v3 {@code manual()} API (Slice 1B).
 */
class DefaultPlatformTracingBaselineTest {

    private static final String INVALID_SPAN_ID = "0000000000000000";

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private DefaultPlatformTracing tracing;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        tracing = new DefaultPlatformTracing(
                OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build());
    }

    @AfterEach
    void tearDown() {
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
        tracerProvider.shutdown();
    }

    @Test
    void childSpan_keepsActiveParent() {
        try (var parent = tracing.manual().operation("parent").start()) {
            try (var child = tracing.manual().operation("child").child().start()) {
                assertThat(tracing.traceContext().traceId()).isPresent();
                assertThat(tracing.traceContext().spanId()).isPresent();
            }
        }

        List<SpanData> spans = exportedSpans();
        assertThat(spans).hasSize(2);
        SpanData parentSpan = findSpanByName(spans, "parent");
        SpanData childSpan = findSpanByName(spans, "child");
        assertThat(childSpan.getTraceId()).isEqualTo(parentSpan.getTraceId());
        assertThat(childSpan.getParentSpanId()).isEqualTo(parentSpan.getSpanId());
    }

    @Test
    void defaultChildSpan_keepsActiveParent() {
        try (var parent = tracing.manual().operation("parent").start()) {
            try (var child = tracing.manual().operation("child").start()) {
                assertThat(tracing.traceContext().traceId()).isPresent();
            }
        }

        List<SpanData> spans = exportedSpans();
        assertThat(spans).hasSize(2);
        SpanData parentSpan = findSpanByName(spans, "parent");
        SpanData childSpan = findSpanByName(spans, "child");
        assertThat(childSpan.getTraceId()).isEqualTo(parentSpan.getTraceId());
        assertThat(childSpan.getParentSpanId()).isEqualTo(parentSpan.getSpanId());
    }

    @Test
    void rootSpan_hasNoParent_evenInsideActiveParentScope() {
        try (var parent = tracing.manual().operation("parent").start()) {
            String parentTraceId = tracing.traceContext().traceId().orElseThrow();
            try (var root = tracing.manual().operation("job").root().start()) {
                assertThat(tracing.traceContext().traceId()).isPresent();
                assertThat(tracing.traceContext().traceId()).isNotEqualTo(java.util.Optional.of(parentTraceId));
            }
        }

        List<SpanData> spans = exportedSpans();
        assertThat(spans).hasSize(2);
        SpanData parentSpan = findSpanByName(spans, "parent");
        SpanData rootSpan = findSpanByName(spans, "job");
        assertHasNoParent(rootSpan);
        assertThat(rootSpan.getSpanContext().isValid()).isTrue();
        assertThat(rootSpan.getTraceId()).isNotEqualTo(parentSpan.getTraceId());
    }

    @Test
    void detachedSpan_hasNoParentAndNoLinks_evenInsideActiveParentScope() {
        try (var parent = tracing.manual().operation("parent").start()) {
            String parentTraceId = tracing.traceContext().traceId().orElseThrow();
            try (var detached = tracing.manual().operation("orphan").detached().start()) {
                assertThat(tracing.traceContext().traceId()).isPresent();
                assertThat(tracing.traceContext().traceId()).isNotEqualTo(java.util.Optional.of(parentTraceId));
            }
        }

        List<SpanData> spans = exportedSpans();
        assertThat(spans).hasSize(2);
        SpanData parentSpan = findSpanByName(spans, "parent");
        SpanData detachedSpan = findSpanByName(spans, "orphan");
        assertHasNoParent(detachedSpan);
        assertThat(detachedSpan.getLinks()).isEmpty();
        assertThat(detachedSpan.getTraceId()).isNotEqualTo(parentSpan.getTraceId());
    }

    @Test
    void spanFromSpec_withLinks_exportsProvidedRemoteLinks() {
        SpanLinkContext link1 = remoteLink("0000000000000000000000000000000a", "0000000000000001");
        SpanLinkContext link2 = remoteLink("0000000000000000000000000000000b", "0000000000000002");
        SpanSpec spec = SpanSpec.builder("kafka.batch.process")
                .category(SpanCategory.KAFKA_CONSUMER)
                .reason(SpanSpecReason.PLATFORM_EDGE_CASE)
                .root()
                .linkedTo(link1, link2)
                .build();

        try (var scope = tracing.manual().spanFromSpec(spec).start()) {
            assertThat(tracing.traceContext().spanId()).isPresent();
        }

        SpanData span = findSpanByName(exportedSpans(), "kafka.batch.process");
        assertThat(span.getKind()).isEqualTo(SpanKind.CONSUMER);
        assertThat(span.getLinks()).hasSize(2);
        assertLinkMatches(span.getLinks().get(0), link1);
        assertLinkMatches(span.getLinks().get(1), link2);
        assertThat(span.getLinks())
                .extracting(link -> link.getSpanContext().isRemote())
                .containsOnly(true);
    }

    @Test
    void traceContext_presentOnlyWhileSpanActive() {
        assertThat(tracing.traceContext().traceId()).isEmpty();
        assertThat(tracing.traceContext().spanId()).isEmpty();

        try (var scope = tracing.manual().operation("active").start()) {
            assertThat(tracing.traceContext().traceId()).isPresent();
            assertThat(tracing.traceContext().spanId()).isPresent();
        }

        assertThat(tracing.traceContext().traceId()).isEmpty();
        assertThat(tracing.traceContext().spanId()).isEmpty();
        assertThat(exportedSpans()).hasSize(1);
    }

    @Test
    void recordException_recordsOnCurrentSpan() {
        try (var scope = tracing.manual().operation("op").start()) {
            scope.recordException(new IllegalStateException("boom"));
        }

        SpanData span = findSpanByName(exportedSpans(), "op");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getEvents()).extracting("name").contains("exception");
    }

    private List<SpanData> exportedSpans() {
        return exporter.getFinishedSpanItems();
    }

    private static SpanLinkContext remoteLink(String traceId, String spanId) {
        return SpanLinkContext.sampled(traceId, spanId);
    }

    private static SpanData findSpanByName(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Span not found: " + name));
    }

    private static void assertHasNoParent(SpanData span) {
        assertThat(span.getParentSpanId()).isIn("", INVALID_SPAN_ID);
    }

    private static void assertLinkMatches(LinkData link, SpanLinkContext expected) {
        assertThat(link.getSpanContext().getTraceId()).isEqualTo(expected.traceId());
        assertThat(link.getSpanContext().getSpanId()).isEqualTo(expected.spanId());
    }
}
