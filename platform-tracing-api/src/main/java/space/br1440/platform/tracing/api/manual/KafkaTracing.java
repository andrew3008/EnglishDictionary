package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

/**
 * Точка входа в трассировку Kafka-транспорта.
 */
public interface KafkaTracing {

    @Nonnull
    KafkaProducerSpanBuilder producer();

    @Nonnull
    KafkaConsumerSpanBuilder consumer();

}
