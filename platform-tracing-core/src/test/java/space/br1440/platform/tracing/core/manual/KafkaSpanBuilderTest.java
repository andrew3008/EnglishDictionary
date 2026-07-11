package space.br1440.platform.tracing.core.manual;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.manual.KafkaTracing;
import space.br1440.platform.tracing.api.manual.ManualTracing;
import space.br1440.platform.tracing.api.semconv.KafkaSemconvVersion;
import space.br1440.platform.tracing.api.semconv.SemconvValidationMode;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.api.span.spec.SpanRelationship;
import space.br1440.platform.tracing.core.runtime.RecordingTracingRuntime;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;
import space.br1440.platform.tracing.core.semconv.policy.SemconvMetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 3C-Kafka hard gate: {@code manual().transport().kafka()} builder foundation.
 */
class KafkaSpanBuilderTest {

    private RecordingTracingRuntime recording;
    private ManualTracing manual;

    @BeforeEach
    void setUp() {
        recording = new RecordingTracingRuntime();
        AttributePolicy strictPolicy = new AttributePolicy(SemconvValidationMode.STRICT, false, SemconvMetrics.NOOP);
        manual = new DefaultManualTracing(recording, strictPolicy);
    }

    @Test
    void transportKafka_returnsNonNullEntryPoint() {
        KafkaTracing kafka = manual.transport().kafka();
        assertThat(kafka).isNotNull();
        assertThat(kafka.producer()).isNotNull();
        assertThat(kafka.consumer()).isNotNull();
    }

    @Test
    void kafkaTracing_hasSemconvVersionMarker() {
        assertThat(KafkaTracing.class.isAnnotationPresent(KafkaSemconvVersion.class)).isTrue();
        assertThat(KafkaTracing.class.getAnnotation(KafkaSemconvVersion.class).value()).isEqualTo("1.28.0");
    }

    @Test
    void kafkaProducerStart_routesSpanSpecThroughTracingRuntime() {
        manual.transport().kafka().producer()
                .destination("orders")
                .operation("publish")
                .start()
                .close();

        assertThat(recording.receivedSpecs()).hasSize(1);
        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.category()).isEqualTo(SpanCategory.KAFKA_PRODUCER);
        assertThat(spec.reason()).isEqualTo(SpanSpecReason.PLATFORM_EDGE_CASE);
        assertThat(spec.name()).isEqualTo("orders publish");
        assertThat(spec.attributes()).containsKey("messaging.system");
        assertThat(spec.attributes()).containsKey("messaging.destination.name");
        assertThat(spec.attributes()).containsKey("messaging.operation");
    }

    @Test
    void kafkaConsumerStart_routesSpanSpecThroughTracingRuntime() {
        manual.transport().kafka().consumer()
                .destination("orders")
                .operation("receive")
                .start()
                .close();

        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.category()).isEqualTo(SpanCategory.KAFKA_CONSUMER);
        assertThat(spec.name()).isEqualTo("orders receive");
    }

    @Test
    void kafkaBatchStart_routesSpanSpecWithPreconfiguredDestination() {
        manual.transport().kafka().consumer()
                .batch("orders")
                .start()
                .close();

        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.category()).isEqualTo(SpanCategory.KAFKA_CONSUMER);
        assertThat(spec.name()).isEqualTo("orders process");
    }

    @Test
    void kafkaBatchRootWithLinks_preservedForSlice5B() {
        SpanLinkContext link = SpanLinkContext.sampled(
                "0102030405060708090a0b0c0d0e0f10",
                "0102030405060708");
        manual.transport().kafka().consumer()
                .batch("orders")
                .root()
                .linkedTo(link)
                .run(() -> {
                });

        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.relationship().kind()).isEqualTo(SpanRelationship.ROOT);
        assertThat(spec.relationship().links()).containsExactly(link);
    }

    @Test
    void missingDestination_rejected() {
        assertThatThrownBy(() ->
                manual.transport().kafka().producer()
                        .operation("publish")
                        .start())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destination");
    }

    @Test
    void missingOperation_rejected() {
        assertThatThrownBy(() ->
                manual.transport().kafka().consumer()
                        .destination("orders")
                        .start())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operation");
    }

    @Test
    void batchBlankDestination_rejected() {
        assertThatThrownBy(() ->
                manual.transport().kafka().consumer().batch("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rootRelationship_works() {
        manual.transport().kafka().producer()
                .destination("orders")
                .operation("publish")
                .root()
                .start()
                .close();

        assertThat(recording.receivedSpecs().getFirst().relationship().kind()).isEqualTo(SpanRelationship.ROOT);
    }
}
