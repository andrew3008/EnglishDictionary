package space.br1440.platform.tracing.autoconfigure.kafka;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.kafka.annotation.KafkaListener;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.core.DefaultPlatformTracing;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaBatchAspectMigrationTest {

    private static final String INVALID_SPAN_ID = "0000000000000000";
    private static final String TRACEPARENT =
            "00-0102030405060708090a0b0c0d0e0f10-0102030405060708-01";
    private static final String TRACE_ID = "0102030405060708090a0b0c0d0e0f10";
    private static final String SPAN_ID = "0102030405060708";

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private OpenTelemetry openTelemetry;
    private PlatformTracing platformTracing;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        platformTracing = new DefaultPlatformTracing(openTelemetry);
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
        assertThat(platformTracing.traceContext().traceId()).isEmpty();
        assertThat(platformTracing.traceContext().spanId()).isEmpty();
    }

    @Test
    void singleTopicBatch_createsRootSpanWithLinksAndMessagingAttributes() {
        ConsumerRecord<String, String> record = recordWithHeaders(
                "orders",
                TRACEPARENT,
                null);

        invokeBatchListener(new BatchListenerStub(), List.of(record));

        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getName()).isEqualTo("orders process");
        assertThat(span.getKind()).isEqualTo(SpanKind.CONSUMER);
        assertThat(span.getParentSpanId()).isIn("", INVALID_SPAN_ID);
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("messaging.system")))
                .isEqualTo("kafka");
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("messaging.destination.name")))
                .isEqualTo("orders");
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("messaging.operation")))
                .isEqualTo("process");
        assertThat(span.getLinks()).hasSize(1);
        assertThat(span.getLinks().getFirst().getSpanContext().getTraceId()).isEqualTo(TRACE_ID);
        assertThat(span.getLinks().getFirst().getSpanContext().getSpanId()).isEqualTo(SPAN_ID);
    }

    @Test
    void multiTopicBatch_destinationFallsBackToListenerId() {
        ConsumerRecord<String, String> recordA = recordWithHeaders("topic-a", TRACEPARENT, null);
        ConsumerRecord<String, String> recordB = recordWithHeaders("topic-b", TRACEPARENT, null);

        invokeBatchListener(new BatchListenerStub(), List.of(recordA, recordB));

        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getName()).isEqualTo("orders-batch-listener process");
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("messaging.destination.name")))
                .isEqualTo("orders-batch-listener");
    }

    @Test
    void configuredPropagator_isUsedForExtraction() {
        openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(new CustomHeaderPropagator()))
                .build();
        platformTracing = new DefaultPlatformTracing(openTelemetry);

        ConsumerRecord<String, String> record = new ConsumerRecord<>("orders", 0, 0L, "k", "v");
        record.headers().add("x-custom-trace", "1".getBytes(StandardCharsets.UTF_8));

        invokeBatchListener(new BatchListenerStub(), List.of(record));

        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getLinks()).hasSize(1);
        assertThat(span.getLinks().getFirst().getSpanContext().getTraceId())
                .isEqualTo(CustomHeaderPropagator.TRACE_ID);
        assertThat(span.getLinks().getFirst().getSpanContext().getSpanId())
                .isEqualTo(CustomHeaderPropagator.SPAN_ID);
    }

    @Test
    void traceStateIsPreservedInLinks() {
        ConsumerRecord<String, String> record = recordWithHeaders(
                "orders",
                TRACEPARENT,
                "vendor=opaque");

        invokeBatchListener(new BatchListenerStub(), List.of(record));

        LinkData link = exporter.getFinishedSpanItems().getFirst().getLinks().getFirst();
        assertThat(link.getSpanContext().getTraceState().get("vendor")).isEqualTo("opaque");
    }

    @Test
    void exceptionPath_recordsExceptionOnceAndRethrows() {
        ConsumerRecord<String, String> record = recordWithHeaders("orders", TRACEPARENT, null);

        assertThatThrownBy(() ->
                invokeBatchListener(new ThrowingBatchListenerStub(), List.of(record)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");

        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getEvents()).anyMatch(event -> "exception".equals(event.getName()));
    }

    private void invokeBatchListener(Object target, List<ConsumerRecord<String, String>> records) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new KafkaBatchLinksAspect(openTelemetry, platformTracing));
        if (target instanceof BatchListenerStub) {
            BatchListenerStub proxy = factory.getProxy();
            proxy.consume(records);
            return;
        }
        if (target instanceof ThrowingBatchListenerStub) {
            ThrowingBatchListenerStub proxy = factory.getProxy();
            proxy.consume(records);
        }
    }

    private static ConsumerRecord<String, String> recordWithHeaders(String topic,
                                                                    String traceparent,
                                                                    String tracestate) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, 0L, "k", "v");
        record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
        if (tracestate != null) {
            record.headers().add("tracestate", tracestate.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    static class BatchListenerStub {
        @KafkaListener(topics = "orders", batch = "true", id = "orders-batch-listener")
        public void consume(List<ConsumerRecord<String, String>> records) {
            // advised body
        }
    }

    static class ThrowingBatchListenerStub {
        @KafkaListener(topics = "orders", batch = "true", id = "orders-batch-listener")
        public void consume(List<ConsumerRecord<String, String>> records) {
            throw new IllegalStateException("boom");
        }
    }

    static final class CustomHeaderPropagator implements TextMapPropagator {

        static final String TRACE_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        static final String SPAN_ID = "bbbbbbbbbbbbbbbb";
        private static final String HEADER = "x-custom-trace";

        @Override
        public Collection<String> fields() {
            return List.of(HEADER);
        }

        @Override
        public <C> void inject(Context context, C carrier, TextMapSetter<C> setter) {
        }

        @Override
        public <C> Context extract(Context context, C carrier, TextMapGetter<C> getter) {
            if (getter.get(carrier, HEADER) == null) {
                return context;
            }
            SpanContext remote = SpanContext.createFromRemoteParent(
                    TRACE_ID,
                    SPAN_ID,
                    TraceFlags.getSampled(),
                    TraceState.getDefault());
            return context.with(Span.wrap(remote));
        }
    }
}
