package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.semconv.KafkaSemconvVersion;

/**
 * Kafka transport tracing entry (Slice 3C).
 */
@KafkaSemconvVersion("1.28.0")
public interface KafkaTracing {

    @Nonnull
    KafkaProducerSpanBuilder producer();

    @Nonnull
    KafkaConsumerSpanBuilder consumer();
}
