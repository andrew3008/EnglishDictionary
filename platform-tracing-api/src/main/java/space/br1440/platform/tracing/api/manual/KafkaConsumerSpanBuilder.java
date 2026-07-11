package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

/**
 * Семантический построитель Kafka consumer под {@link KafkaTracing#consumer()}.
 */
public interface KafkaConsumerSpanBuilder extends ManualSpanBuilder<KafkaConsumerSpanBuilder> {

    @Nonnull
    KafkaConsumerSpanBuilder destination(@Nonnull String topic);

    @Nonnull
    KafkaConsumerSpanBuilder operation(@Nonnull String operation);

    /**
     * Точка входа пакетного consumer'а (семантика ROOT+links финализирована в Slice 5B).
     */
    @Nonnull
    KafkaBatchSpanBuilder batch(@Nonnull String destination);
}
