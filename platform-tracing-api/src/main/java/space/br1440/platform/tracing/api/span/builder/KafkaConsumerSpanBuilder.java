package space.br1440.platform.tracing.api.span.builder;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.semconv.SemconvKeys;

public interface KafkaConsumerSpanBuilder extends PlatformSpanBuilder<KafkaConsumerSpanBuilder> {

    @Nonnull
    default KafkaConsumerSpanBuilder destination(@Nonnull String topic) {
        return attribute(SemconvKeys.MESSAGING_DESTINATION_NAME, topic);
    }

    @Nonnull
    default KafkaConsumerSpanBuilder operation(@Nonnull String operation) {
        return attribute(SemconvKeys.MESSAGING_OPERATION, operation);
    }
}
