package space.br1440.platform.tracing.core.manual;

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
import space.br1440.platform.tracing.api.propagation.TraceparentParser;
import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.core.runtime.otel.OtelTracingRuntimeFactory;
import space.br1440.platform.tracing.core.facade.DefaultPlatformTracing;
import space.br1440.platform.tracing.core.runtime.otel.SpanKinds;
import space.br1440.platform.tracing.api.span.SpanCategory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exporter-level integration test for Kafka batch builder semantics (H2 hardening).
 * <p>
 * Complements {@link KafkaConsumerBatchLinksTest} by asserting exported span kind,
 * messaging semconv attributes, and platform category — not only SpanRelationship/links.
 */
class KafkaBatchSpanBuilderIntegrationTest {

    private static final String INVALID_SPAN_ID = "0000000000000000";
    private static final String TRACEPARENT =
            "00-0102030405060708090a0b0c0d0e0f10-0102030405060708-01";

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private DefaultPlatformTracing platformTracing;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        platformTracing = new DefaultPlatformTracing(OtelTracingRuntimeFactory.create(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()));
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
        assertThat(platformTracing.traceContext().traceId()).isEmpty();
        assertThat(platformTracing.traceContext().spanId()).isEmpty();
    }

    @Test
    void batchRootWithLinks_exportsKafkaConsumerRootWithMessagingAttributes() {
        SpanLinkContext link = SpanLinkContext.sampled(
                "0102030405060708090a0b0c0d0e0f10", "0102030405060708");

        platformTracing.manual()
                .transport()
                .kafka()
                .consumer()
                .batch("orders")
                .root()
                .linkedTo(link)
                .run(() -> { });

        SpanData span = findSpan("orders process");
        assertThat(span.getParentSpanId()).isIn("", INVALID_SPAN_ID);
        assertThat(span.getKind()).isEqualTo(SpanKinds.toSpanKind(SpanCategory.KAFKA_CONSUMER));
        assertThat(span.getLinks()).hasSize(1);
        assertThat(span.getLinks().getFirst().getSpanContext().isRemote()).isTrue();
        assertThat(span.getAttributes().get(key("messaging.system"))).isEqualTo("kafka");
        assertThat(span.getAttributes().get(key("messaging.destination.name"))).isEqualTo("orders");
        assertThat(span.getAttributes().get(key("messaging.operation"))).isEqualTo("process");
        assertThat(span.getAttributes().get(key("platform.trace.type"))).isEqualTo("kafka_consumer");
    }

    @Test
    void batchRootWithTraceparentParser_preservesLink() {
        platformTracing.manual()
                .transport()
                .kafka()
                .consumer()
                .batch("orders")
                .root()
                .fromTraceparent(TRACEPARENT)
                .run(() -> { });

        SpanData span = findSpan("orders process");
        assertThat(span.getParentSpanId()).isIn("", INVALID_SPAN_ID);
        assertThat(span.getLinks()).hasSize(1);
        assertThat(span.getLinks())
                .extracting(LinkData::getSpanContext)
                .extracting(ctx -> ctx.getTraceId() + "/" + ctx.getSpanId())
                .containsExactly("0102030405060708090a0b0c0d0e0f10/0102030405060708");
    }

    @Test
    void batchInsideActiveParent_rootDoesNotBecomeChild() {
        SpanLinkContext link = TraceparentParser.requireTraceparent(TRACEPARENT);

        try (var parent = platformTracing.manual().operation("parent").start()) {
            platformTracing.manual()
                    .transport()
                    .kafka()
                    .consumer()
                    .batch("orders")
                    .root()
                    .linkedTo(link)
                    .run(() -> { });
        }

        SpanData parentSpan = findSpan("parent");
        SpanData batchSpan = findSpan("orders process");
        assertThat(batchSpan.getParentSpanId()).isIn("", INVALID_SPAN_ID);
        assertThat(batchSpan.getTraceId()).isNotEqualTo(parentSpan.getTraceId());
        assertThat(batchSpan.getLinks()).hasSize(1);
        assertThat(batchSpan.getKind()).isEqualTo(SpanKind.CONSUMER);
    }

    @Test
    void childWithLinksRejectedBeforeExport() {
        SpanLinkContext link = SpanLinkContext.sampled(
                "0102030405060708090a0b0c0d0e0f10", "0102030405060708");

        assertThatThrownBy(() ->
                platformTracing.manual()
                        .transport()
                        .kafka()
                        .consumer()
                        .batch("orders")
                        .child()
                        .linkedTo(link)
                        .start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHILD");

        assertThat(findBatchSpans()).isEmpty();
    }

    @Test
    void detachedWithLinksRejectedBeforeExport() {
        SpanLinkContext link = SpanLinkContext.sampled(
                "0102030405060708090a0b0c0d0e0f10", "0102030405060708");

        assertThatThrownBy(() ->
                platformTracing.manual()
                        .transport()
                        .kafka()
                        .consumer()
                        .batch("orders")
                        .detached()
                        .linkedTo(link)
                        .start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DETACHED");

        assertThat(findBatchSpans()).isEmpty();
    }

    private static io.opentelemetry.api.common.AttributeKey<String> key(String name) {
        return io.opentelemetry.api.common.AttributeKey.stringKey(name);
    }

    private SpanData findSpan(String name) {
        return exporter.getFinishedSpanItems().stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Span not found: " + name));
    }

    private List<SpanData> findBatchSpans() {
        return exporter.getFinishedSpanItems().stream()
                .filter(span -> "orders process".equals(span.getName()))
                .toList();
    }
}
