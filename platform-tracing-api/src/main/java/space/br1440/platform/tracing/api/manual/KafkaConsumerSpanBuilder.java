package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.semconv.annotation.KafkaSemconvVersion;

/**
 * Семантический построитель Kafka consumer span {@link KafkaTracing#consumer()}.
 */
@KafkaSemconvVersion("1.28.0")
public interface KafkaConsumerSpanBuilder extends ManualSpanBuilder<KafkaConsumerSpanBuilder> {

    @Nonnull
    KafkaConsumerSpanBuilder destination(@Nonnull String topic);

    @Nonnull
    KafkaConsumerSpanBuilder operation(@Nonnull String operation);

    /**
     * Точка входа пакетного consumer'а (семантика ROOT+links).
     */
    @Nonnull
    KafkaBatchSpanBuilder batch(@Nonnull String destination);

}
