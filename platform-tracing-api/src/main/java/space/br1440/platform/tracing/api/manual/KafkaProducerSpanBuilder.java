package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.semconv.KafkaSemconvVersion;

/**
 * Семантический построитель Kafka producer под {@link KafkaTracing#producer()}.
 */
@KafkaSemconvVersion("1.28.0")
public interface KafkaProducerSpanBuilder extends ManualSpanBuilder<KafkaProducerSpanBuilder> {

    @Nonnull
    KafkaProducerSpanBuilder destination(@Nonnull String topic);

    @Nonnull
    KafkaProducerSpanBuilder operation(@Nonnull String operation);
}
