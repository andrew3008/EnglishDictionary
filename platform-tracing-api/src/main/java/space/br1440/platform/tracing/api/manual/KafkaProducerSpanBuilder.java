package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

/**
 * Семантический построитель Kafka producer под {@link KafkaTracing#producer()}.
 */
public interface KafkaProducerSpanBuilder extends ManualSpanBuilder<KafkaProducerSpanBuilder> {

    @Nonnull
    KafkaProducerSpanBuilder destination(@Nonnull String topic);

    @Nonnull
    KafkaProducerSpanBuilder operation(@Nonnull String operation);
}
