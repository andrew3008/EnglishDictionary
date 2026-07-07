package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

/**
 * Kafka consumer semantic builder under {@link KafkaTracing#consumer()}.
 */
public interface KafkaConsumerSpanBuilder extends PlatformSpanBuilder<KafkaConsumerSpanBuilder> {

    @Nonnull
    KafkaConsumerSpanBuilder destination(@Nonnull String topic);

    @Nonnull
    KafkaConsumerSpanBuilder operation(@Nonnull String operation);

    /**
     * Batch consumer entry point (ROOT+links semantics finalized in Slice 5B).
     */
    @Nonnull
    KafkaBatchSpanBuilder batch(@Nonnull String destination);
}
