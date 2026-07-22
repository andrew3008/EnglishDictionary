package space.br1440.platform.tracing.otel;
import space.br1440.platform.tracing.otel.runtime.otel.OtelTracingRuntimeFactory;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;

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
import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 0A baseline characterization tests migrated to v3 {@code spans()} API (Slice 1B).
 */
class DefaultTraceOperationsBaselineTest {

    private static final String INVALID_SPAN_ID = "0000000000000000";

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private DefaultTraceOperations tracing;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        tracing = new DefaultTraceOperations(OtelTracingRuntimeFactory.create(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()));
    }

    @AfterEach
    void tearDown() {
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
        tracerProvider.shutdown();
    }

    @Test
    void childSpan_keepsActiveParent() {
        try (var parent = tracing.spans().operation("parent").start()) {
            try (var child = tracing.spans().operation("child").child().start()) {
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
        try (var parent = tracing.spans().operation("parent").start()) {
            try (var child = tracing.spans().operation("child").start()) {
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
        try (var parent = tracing.spans().operation("parent").start()) {
            String parentTraceId = tracing.traceContext().traceId().orElseThrow();
            try (var root = tracing.spans().operation("job").root().start()) {
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
        try (var parent = tracing.spans().operation("parent").start()) {
            String parentTraceId = tracing.traceContext().traceId().orElseThrow();
            try (var detached = tracing.spans().operation("orphan").detached().start()) {
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
    void fromSpec_withLinks_exportsProvidedRemoteLinks() {
        RemoteSpanLink link1 = remoteLink("0000000000000000000000000000000a", "0000000000000001");
        RemoteSpanLink link2 = remoteLink("0000000000000000000000000000000b", "0000000000000002");
        SpanSpec spec = SpanSpec.builder("kafka.batch.process")
                .category(SpanCategory.KAFKA_CONSUMER)
                .reason(SpanSpecReason.PLATFORM_EDGE_CASE)
                .root()
                .linkedTo(link1, link2)
                .build();

        try (var scope = tracing.spans().fromSpec(spec).start()) {
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

        try (var scope = tracing.spans().operation("active").start()) {
            assertThat(tracing.traceContext().traceId()).isPresent();
            assertThat(tracing.traceContext().spanId()).isPresent();
        }

        assertThat(tracing.traceContext().traceId()).isEmpty();
        assertThat(tracing.traceContext().spanId()).isEmpty();
        assertThat(exportedSpans()).hasSize(1);
    }

    @Test
    void recordException_recordsOnCurrentSpan() {
        try (var scope = tracing.spans().operation("op").start()) {
            scope.recordException(new IllegalStateException("boom"));
        }

        SpanData span = findSpanByName(exportedSpans(), "op");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getEvents()).extracting("name").contains("exception");
    }

    private List<SpanData> exportedSpans() {
        return exporter.getFinishedSpanItems();
    }

    private static RemoteSpanLink remoteLink(String traceId, String spanId) {
        return RemoteSpanLink.sampled(traceId, spanId);
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

    private static void assertLinkMatches(LinkData link, RemoteSpanLink expected) {
        assertThat(link.getSpanContext().getTraceId()).isEqualTo(expected.traceId());
        assertThat(link.getSpanContext().getSpanId()).isEqualTo(expected.spanId());
    }
}
