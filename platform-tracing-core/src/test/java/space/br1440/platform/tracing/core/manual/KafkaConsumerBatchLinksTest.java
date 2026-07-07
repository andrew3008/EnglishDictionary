package space.br1440.platform.tracing.core.manual;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.RemoteContext;
import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.core.DefaultPlatformTracing;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 5B hard gate: Kafka consumer batch ROOT+links and links-before-start semantics.
 */
class KafkaConsumerBatchLinksTest {

    private static final String INVALID_SPAN_ID = "0000000000000000";
    private static final String TRACEPARENT_A =
            "00-0102030405060708090a0b0c0d0e0f10-0102030405060708-01";
    private static final String TRACEPARENT_B =
            "00-020406080a0c0e10121416181a1c1e20-020406080a0c0e10-01";

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
        tracerProvider.shutdown();
        assertThat(tracing.traceContext().traceId()).isEmpty();
        assertThat(tracing.traceContext().spanId()).isEmpty();
    }

    @Test
    void kafkaBatchRootWithLinkedTo_createsRootSpanWithRemoteLinks() {
        SpanLinkContext link = SpanLinkContext.sampled(
                "0102030405060708090a0b0c0d0e0f10", "0102030405060708");
        tracing.manual().transport().kafka().consumer()
                .batch("orders")
                .root()
                .linkedTo(link)
                .start()
                .close();

        SpanData span = findSpan("orders process");
        assertThat(span.getParentSpanId()).isIn("", INVALID_SPAN_ID);
        assertThat(span.getLinks()).hasSize(1);
        assertThat(span.getLinks().getFirst().getSpanContext().isRemote()).isTrue();
    }

    @Test
    void kafkaBatchRootWithFromRemoteContext_parsesTraceparentIntoLinks() {
        tracing.manual().transport().kafka().consumer()
                .batch("orders")
                .root()
                .fromRemoteContext(TRACEPARENT_A, TRACEPARENT_B)
                .start()
                .close();

        SpanData span = findSpan("orders process");
        assertThat(span.getLinks()).hasSize(2);
        assertThat(span.getLinks())
                .extracting(LinkData::getSpanContext)
                .extracting(ctx -> ctx.getTraceId() + "/" + ctx.getSpanId())
                .containsExactly(
                        "0102030405060708090a0b0c0d0e0f10/0102030405060708",
                        "020406080a0c0e10121416181a1c1e20/020406080a0c0e10");
    }

    @Test
    void kafkaBatchRootInsideActiveParent_isNotChildSpan() {
        try (var parent = tracing.manual().operation("parent").start()) {
            tracing.manual().transport().kafka().consumer()
                    .batch("orders")
                    .root()
                    .fromRemoteContext(TRACEPARENT_A)
                    .start()
                    .close();
        }

        SpanData parentSpan = findSpan("parent");
        SpanData batchSpan = findSpan("orders process");
        assertThat(batchSpan.getParentSpanId()).isIn("", INVALID_SPAN_ID);
        assertThat(batchSpan.getTraceId()).isNotEqualTo(parentSpan.getTraceId());
        assertThat(batchSpan.getLinks()).hasSize(1);
    }

    @Test
    void kafkaBatchChildWithLinks_rejectedBeforeStart() {
        SpanLinkContext link = RemoteContext.requireTraceparent(TRACEPARENT_A);
        assertThatThrownBy(() ->
                tracing.manual().transport().kafka().consumer()
                        .batch("orders")
                        .child()
                        .linkedTo(link)
                        .start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHILD");
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void kafkaBatchDetachedWithLinks_rejectedBeforeStart() {
        SpanLinkContext link = RemoteContext.requireTraceparent(TRACEPARENT_A);
        assertThatThrownBy(() ->
                tracing.manual().transport().kafka().consumer()
                        .batch("orders")
                        .detached()
                        .linkedTo(link)
                        .start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DETACHED");
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void spanHandle_hasNoPostStartAddLinkApi() {
        assertThat(Arrays.stream(SpanHandle.class.getMethods())
                .map(java.lang.reflect.Method::getName)
                .noneMatch("addLink"::equals)).isTrue();
    }

    private SpanData findSpan(String name) {
        List<SpanData> spans = exporter.getFinishedSpanItems();
        return spans.stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Span not found: " + name));
    }
}
