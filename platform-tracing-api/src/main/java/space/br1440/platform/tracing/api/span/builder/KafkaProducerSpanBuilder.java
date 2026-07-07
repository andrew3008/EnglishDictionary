package space.br1440.platform.tracing.api.span.builder;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.semconv.SemconvKeys;

public interface KafkaProducerSpanBuilder extends PlatformSpanBuilder<KafkaProducerSpanBuilder> {

    @Nonnull
    default KafkaProducerSpanBuilder destination(@Nonnull String topic) {
        return attribute(SemconvKeys.MESSAGING_DESTINATION_NAME, topic);
    }

    @Nonnull
    default KafkaProducerSpanBuilder operation(@Nonnull String operation) {
        return attribute(SemconvKeys.MESSAGING_OPERATION, operation);
    }
}
