package space.br1440.platform.tracing.otel;
import space.br1440.platform.tracing.otel.runtime.otel.OtelTracingRuntimeFactory;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;

import io.opentelemetry.api.trace.SpanKind;
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

class DefaultTraceOperationsTest {

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
    }

    @Test
    void childSpan_наследует_traceId_родителя() {
        try (var parent = tracing.spans().operation("parent").start()) {
            try (var child = tracing.spans().operation("child").child().start()) {
                assertThat(tracing.traceContext().traceId()).isPresent();
            }
        }

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);
        SpanData parentSpan = findSpan(spans, "parent");
        SpanData childSpan = findSpan(spans, "child");
        assertThat(childSpan.getTraceId()).isEqualTo(parentSpan.getTraceId());
        assertThat(childSpan.getParentSpanId()).isEqualTo(parentSpan.getSpanId());
    }

    @Test
    void facadeDisabled_не_создаёт_span() {
        tracing.setFacadeEnabled(false);
        assertThat(tracing.isFacadeEnabled()).isFalse();

        try (var scope = tracing.spans().operation("disabled").start()) {
            assertThat(tracing.traceContext().traceId()).as("активного span'а нет — фасад выключен").isEmpty();
        }

        assertThat(exporter.getFinishedSpanItems()).as("span не создаётся при выключенном фасаде").isEmpty();

        tracing.setFacadeEnabled(true);
        try (var scope = tracing.spans().operation("enabled").start()) {
            assertThat(tracing.traceContext().traceId()).isPresent();
        }
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    void rootSpan_создаёт_новую_трассу_без_родителя() {
        try (var parent = tracing.spans().operation("parent").start()) {
            try (var root = tracing.spans().operation("job").root().start()) {
                assertThat(tracing.traceContext().traceId()).isPresent();
            }
        }

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);
        SpanData parentSpan = findSpan(spans, "parent");
        SpanData rootSpan = findSpan(spans, "job");
        assertThat(rootSpan.getParentSpanId()).isIn("", INVALID_SPAN_ID);
        assertThat(rootSpan.getTraceId()).isNotEqualTo(parentSpan.getTraceId());
    }

    @Test
    void detachedSpan_не_имеет_родителя() {
        try (var detached = tracing.spans().operation("orphan").detached().start()) {
            assertThat(tracing.traceContext().traceId()).isPresent();
        }

        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getParentSpanId()).isIn("", INVALID_SPAN_ID);
    }

    @Test
    void fromSpec_создаёт_span_с_remote_links() {
        RemoteSpanLink link1 = RemoteSpanLink.sampled(
                "0000000000000000000000000000000a", "0000000000000001");
        RemoteSpanLink link2 = RemoteSpanLink.sampled(
                "0000000000000000000000000000000b", "0000000000000002");
        SpanSpec spec = SpanSpec.builder("kafka.batch.process")
                .category(SpanCategory.KAFKA_CONSUMER)
                .reason(SpanSpecReason.PLATFORM_EDGE_CASE)
                .root()
                .linkedTo(link1, link2)
                .build();

        try (var scope = tracing.spans().fromSpec(spec).start()) {
            // success path
        }

        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getKind()).isEqualTo(SpanKind.CONSUMER);
        assertThat(span.getLinks()).hasSize(2);
        assertThat(span.getLinks())
                .extracting(LinkData::getSpanContext)
                .extracting(ctx -> ctx.getTraceId() + "/" + ctx.getSpanId())
                .containsExactly(
                        "0000000000000000000000000000000a/0000000000000001",
                        "0000000000000000000000000000000b/0000000000000002");
        assertThat(span.getLinks())
                .extracting(link -> link.getSpanContext().isRemote())
                .containsOnly(true);
    }

    @Test
    void rootSpan_эквивалентен_explicit_root() {
        try (var scope = tracing.spans().operation("job").root().start()) {
            assertThat(tracing.traceContext().spanId()).isPresent();
        }

        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getParentSpanId()).isIn("", INVALID_SPAN_ID);
    }

    private static SpanData findSpan(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Span not found: " + name));
    }
}
