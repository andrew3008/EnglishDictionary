package space.br1440.platform.tracing.otel.span.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.api.semconv.annotation.KafkaSemconvVersion;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanFactory;
import space.br1440.platform.tracing.api.span.builder.KafkaBatchSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.KafkaConsumerSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.KafkaProducerSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.KafkaTracing;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanRelationship;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidationMetrics;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidator;
import space.br1440.platform.tracing.otel.runtime.RecordingTracingRuntime;
import space.br1440.platform.tracing.otel.span.DefaultSpanFactory;

class KafkaSpanBuilderTest {

    private RecordingTracingRuntime recording;
    private SpanFactory manual;

    @BeforeEach
    void setUp() {
        recording = new RecordingTracingRuntime();
        SpanAttributeValidator strictPolicy = new SpanAttributeValidator(
                SpanAttributeValidationLevel.ERROR,
                SpanAttributeValidationMetrics.NOOP
        );

        manual = new DefaultSpanFactory(recording, strictPolicy);
    }

    @Test
    void transportKafka_returnsNonNullEntryPoint() {
        KafkaTracing kafka = manual.transport().kafka();
        assertThat(kafka).isNotNull();
        assertThat(kafka.producer()).isNotNull();
        assertThat(kafka.consumer()).isNotNull();
    }

    @Test
    void kafkaBuilders_haveExpectedSemconvVersionMarker() {
        final String expected = "1.28.0";

        assertThat(KafkaProducerSpanBuilder.class.getAnnotation(KafkaSemconvVersion.class))
                .isNotNull()
                .extracting(KafkaSemconvVersion::value).isEqualTo(expected);

        assertThat(KafkaConsumerSpanBuilder.class.getAnnotation(KafkaSemconvVersion.class))
                .isNotNull()
                .extracting(KafkaSemconvVersion::value).isEqualTo(expected);

        assertThat(KafkaBatchSpanBuilder.class.getAnnotation(KafkaSemconvVersion.class))
                .isNotNull()
                .extracting(KafkaSemconvVersion::value).isEqualTo(expected);

        assertThat(KafkaTracing.class.getAnnotation(KafkaSemconvVersion.class)).isNull();
    }

    @Test
    void kafkaProducerStart_routesSpanSpecThroughTracingRuntime() {
        try (SpanHandle handle = manual.transport().kafka().producer()
                .destination("orders")
                .operation("publish")
                .start()) {
            assertThat(handle).isNotNull();
        }

        assertThat(recording.receivedSpecs()).hasSize(1);
        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.category()).isEqualTo(SpanCategory.KAFKA_PRODUCER);
        assertThat(spec.reason()).isEmpty();
        assertThat(spec.name()).isEqualTo("orders publish");
        assertThat(spec.attributes()).containsKey("messaging.system");
        assertThat(spec.attributes()).containsKey("messaging.destination.name");
        assertThat(spec.attributes()).containsKey("messaging.operation");
    }

    @Test
    void kafkaConsumerStart_routesSpanSpecThroughTracingRuntime() {
        try (SpanHandle handle = manual.transport().kafka().consumer()
                .destination("orders")
                .operation("receive")
                .start()) {
            assertThat(handle).isNotNull();
        }

        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.category()).isEqualTo(SpanCategory.KAFKA_CONSUMER);
        assertThat(spec.name()).isEqualTo("orders receive");
    }

    @Test
    void kafkaBatchStart_routesSpanSpecWithPreconfiguredDestination() {
        try (SpanHandle handle = manual.transport().kafka().consumer()
                .batch("orders")
                .start()) {
            assertThat(handle).isNotNull();
        }

        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.category()).isEqualTo(SpanCategory.KAFKA_CONSUMER);
        assertThat(spec.name()).isEqualTo("orders process");
    }

    @Test
    void kafkaBatchRootWithLinks_preservesRootAndLinks() {
        RemoteSpanLink link = RemoteSpanLink.sampled(
                "0102030405060708090a0b0c0d0e0f10",
                "0102030405060708"
        );

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
        assertThatThrownBy(() -> {
            try (SpanHandle handle = manual.transport().kafka().producer()
                    .operation("publish")
                    .start()) {
                fail("missing destination must fail at start(), got " + handle);
            }
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destination");
    }

    @Test
    void missingOperation_rejected() {
        assertThatThrownBy(() -> {
            try (SpanHandle handle = manual.transport().kafka().consumer()
                    .destination("orders")
                    .start()) {
                fail("missing operation must fail at start(), got " + handle);
            }
        })
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
        try (SpanHandle handle = manual.transport().kafka().producer()
                .destination("orders")
                .operation("publish")
                .root()
                .start()) {
            assertThat(handle).isNotNull();
        }

        assertThat(recording.receivedSpecs().getFirst().relationship().kind()).isEqualTo(SpanRelationship.ROOT);
    }
}
