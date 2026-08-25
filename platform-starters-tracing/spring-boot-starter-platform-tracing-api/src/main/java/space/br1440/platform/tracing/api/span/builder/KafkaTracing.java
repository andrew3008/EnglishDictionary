package space.br1440.platform.tracing.api.span.builder;

import jakarta.annotation.Nonnull;

public interface KafkaTracing {

    @Nonnull
    KafkaProducerSpanBuilder producer();

    @Nonnull
    KafkaConsumerSpanBuilder consumer();

}
