package space.br1440.platform.tracing.otel.manual;

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
import space.br1440.platform.tracing.api.span.spec.SpanRelationshipSpec;
import space.br1440.platform.tracing.otel.runtime.otel.OtelTracingRuntimeFactory;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hard gate: {@link SpanRelationshipSpec} runtime relationship.
 */
class SpanRelationshipSpecTest {

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
        tracerProvider.shutdown();
        assertThat(tracing.traceContext().traceId()).isEmpty();
        assertThat(tracing.traceContext().spanId()).isEmpty();
    }

    @Test
    void childInsideActiveParent_inheritsParentSpanIdAndTraceId() {
        try (var parent = tracing.spans().operation("parent").start()) {
            try (var child = tracing.spans().operation("child").child().start()) {
                assertThat(tracing.traceContext().traceId()).isPresent();
            }
        }

        SpanData parentSpan = findSpan("parent");
        SpanData childSpan = findSpan("child");
        assertThat(childSpan.getTraceId()).isEqualTo(parentSpan.getTraceId());
        assertThat(childSpan.getParentSpanId()).isEqualTo(parentSpan.getSpanId());
    }

    @Test
    void rootInsideActiveParent_hasInvalidParentAndIndependentTraceId() {
        try (var parent = tracing.spans().operation("parent").start()) {
            try (var root = tracing.spans().operation("job").root().start()) {
                assertThat(tracing.traceContext().traceId()).isPresent();
            }
        }

        SpanData parentSpan = findSpan("parent");
        SpanData rootSpan = findSpan("job");
        assertThat(rootSpan.getParentSpanId()).isIn("", INVALID_SPAN_ID);
        assertThat(rootSpan.getTraceId()).isNotEqualTo(parentSpan.getTraceId());
    }

    @Test
    void detachedInsideActiveParent_hasInvalidParentIndependentTraceIdAndNoLinks() {
        try (var parent = tracing.spans().operation("parent").start()) {
            try (var detached = tracing.spans().operation("orphan").detached().start()) {
                assertThat(tracing.traceContext().traceId()).isPresent();
            }
        }

        SpanData parentSpan = findSpan("parent");
        SpanData detachedSpan = findSpan("orphan");
        assertThat(detachedSpan.getParentSpanId()).isIn("", INVALID_SPAN_ID);
        assertThat(detachedSpan.getTraceId()).isNotEqualTo(parentSpan.getTraceId());
        assertThat(detachedSpan.getLinks()).isEmpty();
    }

    @Test
    void rootWithLinks_producesRootSpanWithRemoteLinks() {
        RemoteSpanLink link = RemoteSpanLink.sampled(
                "0000000000000000000000000000000a", "0000000000000001");
        tracing.spans().operation("linked-root").root().linkedTo(link).start().close();

        SpanData span = findSpan("linked-root");
        assertThat(span.getParentSpanId()).isIn("", INVALID_SPAN_ID);
        assertThat(span.getLinks()).hasSize(1);
        assertThat(span.getLinks().getFirst().getSpanContext().isRemote()).isTrue();
    }

    @Test
    void detachedWithLinks_failsBeforeSpanStart() {
        RemoteSpanLink link = RemoteSpanLink.sampled(
                "0102030405060708090a0b0c0d0e0f10", "0102030405060708");
        assertThatThrownBy(() ->
                tracing.spans().operation("bad-detached").detached().linkedTo(link).start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DETACHED");
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void childWithLinks_failsBeforeSpanStart() {
        RemoteSpanLink link = RemoteSpanLink.sampled(
                "0102030405060708090a0b0c0d0e0f10", "0102030405060708");
        assertThatThrownBy(() ->
                tracing.spans().operation("bad-child").child().linkedTo(link).start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHILD");
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void linkedToThenRoot_isValid() {
        RemoteSpanLink link = RemoteSpanLink.sampled(
                "0102030405060708090a0b0c0d0e0f10", "0102030405060708");
        tracing.spans().operation("order-root").linkedTo(link).root().start().close();

        SpanData span = findSpan("order-root");
        assertThat(span.getLinks()).hasSize(1);
        assertThat(span.getLinks())
                .extracting(LinkData::getSpanContext)
                .extracting(ctx -> ctx.getTraceId() + "/" + ctx.getSpanId())
                .containsExactly("0102030405060708090a0b0c0d0e0f10/0102030405060708");
    }

    @Test
    void linkedToThenDetached_isInvalid() {
        RemoteSpanLink link = RemoteSpanLink.sampled(
                "0102030405060708090a0b0c0d0e0f10", "0102030405060708");
        assertThatThrownBy(() ->
                tracing.spans().operation("bad-order").linkedTo(link).detached().start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DETACHED");
    }

    @Test
    void detachedWithLinks_rejectedBeforeSpanStartAtSpecBuild() {
        RemoteSpanLink link = RemoteSpanLink.sampled(
                "0102030405060708090a0b0c0d0e0f10", "0102030405060708");
        assertThatThrownBy(() -> SpanSpec.builder("invalid-detached")
                .category(SpanCategory.INTERNAL)
                .reason(SpanSpecReason.PLATFORM_EDGE_CASE)
                .detached()
                .linkedTo(link)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DETACHED");
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void validateRelationshipLinks_rejectsChildLinks() {
        RemoteSpanLink link = RemoteSpanLink.sampled(
                "0102030405060708090a0b0c0d0e0f10", "0102030405060708");

        assertThatThrownBy(() -> SpanRelationshipSpec.validateRelationshipLinks(
                space.br1440.platform.tracing.api.span.spec.SpanRelationship.CHILD,
                List.of(link)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHILD");
    }
    private SpanData findSpan(String name) {
        List<SpanData> spans = exporter.getFinishedSpanItems();
        return spans.stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Span not found: " + name));
    }
}
