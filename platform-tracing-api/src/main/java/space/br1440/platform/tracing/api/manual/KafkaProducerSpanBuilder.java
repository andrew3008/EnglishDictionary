package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

/**
 * Kafka producer semantic builder under {@link KafkaTracing#producer()}.
 */
public interface KafkaProducerSpanBuilder extends PlatformSpanBuilder<KafkaProducerSpanBuilder> {

    @Nonnull
    KafkaProducerSpanBuilder destination(@Nonnull String topic);

    @Nonnull
    KafkaProducerSpanBuilder operation(@Nonnull String operation);
}
