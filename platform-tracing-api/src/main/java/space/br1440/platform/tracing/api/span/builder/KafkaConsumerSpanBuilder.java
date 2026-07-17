package space.br1440.platform.tracing.api.span.builder;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.semconv.annotation.KafkaSemconvVersion;

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
