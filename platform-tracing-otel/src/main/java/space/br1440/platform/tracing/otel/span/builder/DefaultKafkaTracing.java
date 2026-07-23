package space.br1440.platform.tracing.otel.span.builder;

import java.util.Objects;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.builder.KafkaBatchSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.KafkaConsumerSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.KafkaProducerSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.KafkaTracing;
import space.br1440.platform.tracing.api.span.builder.ManualSpanBuilder;
import space.br1440.platform.tracing.api.span.spec.SpanSpecAttributeValue;
import space.br1440.platform.tracing.otel.propagation.OtelTraceparentReader;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;
import space.br1440.platform.tracing.otel.span.spec.DefaultSpanSpecFactory;

final class DefaultKafkaTracing implements KafkaTracing {

    private final DefaultSpanSpecFactory specFactory;
    private final OtelTraceparentReader traceparentReader;

    DefaultKafkaTracing(@Nonnull DefaultSpanSpecFactory specFactory,
                        @Nonnull OtelTraceparentReader traceparentReader) {
        this.specFactory = Objects.requireNonNull(specFactory, "specFactory");
        this.traceparentReader = Objects.requireNonNull(traceparentReader, "traceparentReader");
    }

    @Override
    @Nonnull
    public KafkaProducerSpanBuilder producer() {
        return new KafkaProducerSpanBuilderImpl(specFactory, traceparentReader);
    }

    @Override
    @Nonnull
    public KafkaConsumerSpanBuilder consumer() {
        return new KafkaConsumerSpanBuilderImpl(specFactory, traceparentReader);
    }

    private static abstract class AbstractKafkaSpanBuilder<B extends ManualSpanBuilder<B>>
            extends AbstractSemanticSpanBuilder<B> {

        AbstractKafkaSpanBuilder(@Nonnull DefaultSpanSpecFactory specFactory,
                                 @Nonnull OtelTraceparentReader traceparentReader,
                                 @Nonnull SpanCategory category,
                                 @Nonnull String builderName) {
            super(specFactory, traceparentReader, category, category.value(), builderName);
            putAttribute(SemconvKeys.MESSAGING_SYSTEM.getKey(), SpanSpecAttributeValue.of("kafka"));
        }

        @Override
        protected void validateBeforeExecution() {
            requireAttribute(SemconvKeys.MESSAGING_DESTINATION_NAME.getKey(), "destination");
            requireAttribute(SemconvKeys.MESSAGING_OPERATION.getKey(), "operation");
        }

        private void requireAttribute(@Nonnull String key, @Nonnull String label) {
            if (!attributes.containsKey(key)) {
                throw new IllegalArgumentException(label + " is required");
            }
        }
    }

    private static final class KafkaProducerSpanBuilderImpl extends AbstractKafkaSpanBuilder<KafkaProducerSpanBuilder>
            implements KafkaProducerSpanBuilder {

        KafkaProducerSpanBuilderImpl(@Nonnull DefaultSpanSpecFactory specFactory,
                                     @Nonnull OtelTraceparentReader traceparentReader) {
            super(specFactory, traceparentReader,
                    SpanCategory.KAFKA_PRODUCER, "KafkaProducerSpanBuilder");
        }

        @Override
        protected KafkaProducerSpanBuilder self() {
            return this;
        }

        @Override
        @Nonnull
        public KafkaProducerSpanBuilder destination(@Nonnull String topic) {
            putAttribute(SemconvKeys.MESSAGING_DESTINATION_NAME.getKey(), SpanSpecAttributeValue.of(topic));
            return this;
        }

        @Override
        @Nonnull
        public KafkaProducerSpanBuilder operation(@Nonnull String operation) {
            putAttribute(SemconvKeys.MESSAGING_OPERATION.getKey(), SpanSpecAttributeValue.of(operation));
            return this;
        }
    }

    private static final class KafkaConsumerSpanBuilderImpl extends AbstractKafkaSpanBuilder<KafkaConsumerSpanBuilder>
            implements KafkaConsumerSpanBuilder {

        KafkaConsumerSpanBuilderImpl(@Nonnull DefaultSpanSpecFactory specFactory,
                                     @Nonnull OtelTraceparentReader traceparentReader) {
            super(specFactory, traceparentReader,
                    SpanCategory.KAFKA_CONSUMER, "KafkaConsumerSpanBuilder");
        }

        @Override
        protected KafkaConsumerSpanBuilder self() {
            return this;
        }

        @Override
        @Nonnull
        public KafkaConsumerSpanBuilder destination(@Nonnull String topic) {
            putAttribute(SemconvKeys.MESSAGING_DESTINATION_NAME.getKey(), SpanSpecAttributeValue.of(topic));
            return this;
        }

        @Override
        @Nonnull
        public KafkaConsumerSpanBuilder operation(@Nonnull String operation) {
            putAttribute(SemconvKeys.MESSAGING_OPERATION.getKey(), SpanSpecAttributeValue.of(operation));
            return this;
        }

        @Override
        @Nonnull
        public KafkaBatchSpanBuilder batch(@Nonnull String destination) {
            Objects.requireNonNull(destination, "destination");

            if (destination.isBlank()) {
                throw new IllegalArgumentException("destination must not be blank");
            }

            return new KafkaBatchSpanBuilderImpl(specFactory, traceparentReader, destination);
        }
    }

    private static final class KafkaBatchSpanBuilderImpl extends AbstractKafkaSpanBuilder<KafkaBatchSpanBuilder>
            implements KafkaBatchSpanBuilder {

        KafkaBatchSpanBuilderImpl(@Nonnull DefaultSpanSpecFactory specFactory,
                                  @Nonnull OtelTraceparentReader traceparentReader,
                                  @Nonnull String destination) {
            super(specFactory, traceparentReader,
                    SpanCategory.KAFKA_CONSUMER, "KafkaBatchSpanBuilder");
            putAttribute(SemconvKeys.MESSAGING_DESTINATION_NAME.getKey(), SpanSpecAttributeValue.of(destination));
            putAttribute(SemconvKeys.MESSAGING_OPERATION.getKey(), SpanSpecAttributeValue.of("process"));
        }

        @Override
        protected KafkaBatchSpanBuilder self() {
            return this;
        }
    }
}
