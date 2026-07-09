package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.manual.KafkaBatchSpanBuilder;
import space.br1440.platform.tracing.api.manual.KafkaConsumerSpanBuilder;
import space.br1440.platform.tracing.api.manual.KafkaProducerSpanBuilder;
import space.br1440.platform.tracing.api.manual.KafkaTracing;
import space.br1440.platform.tracing.api.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.SpanAttributeValue;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.core.runtime.TracingRuntime;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;

import java.util.Objects;

final class DefaultKafkaTracing implements KafkaTracing {

    private final TracingRuntime implementation;
    private final AttributePolicy policy;

    DefaultKafkaTracing(@Nonnull TracingRuntime implementation,
                          @Nonnull AttributePolicy policy) {
        this.implementation = Objects.requireNonNull(implementation, "implementation");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    @Nonnull
    public KafkaProducerSpanBuilder producer() {
        return new KafkaProducerSpanBuilderImpl(implementation, policy);
    }

    @Override
    @Nonnull
    public KafkaConsumerSpanBuilder consumer() {
        return new KafkaConsumerSpanBuilderImpl(implementation, policy);
    }
}

abstract class AbstractKafkaSpanBuilder<B extends space.br1440.platform.tracing.api.manual.PlatformSpanBuilder<B>>
        extends AbstractSemanticSpanBuilder<B> {

    AbstractKafkaSpanBuilder(@Nonnull TracingRuntime implementation,
                             @Nonnull AttributePolicy policy,
                             @Nonnull SpanCategory category,
                             @Nonnull String builderName) {
        super(implementation, policy, category, category.value(), builderName);
        putAttribute(SemconvKeys.MESSAGING_SYSTEM.getKey(), SpanAttributeValue.of("kafka"));
    }

    @Override
    protected SpanSpec toSpanSpec() {
        requireAttribute(SemconvKeys.MESSAGING_DESTINATION_NAME.getKey(), "destination");
        requireAttribute(SemconvKeys.MESSAGING_OPERATION.getKey(), "operation");
        return super.toSpanSpec();
    }

    private void requireAttribute(@Nonnull String key, @Nonnull String label) {
        if (!attributes.containsKey(key)) {
            throw new IllegalArgumentException(label + " is required");
        }
    }
}

final class KafkaProducerSpanBuilderImpl extends AbstractKafkaSpanBuilder<KafkaProducerSpanBuilder>
        implements KafkaProducerSpanBuilder {

    KafkaProducerSpanBuilderImpl(@Nonnull TracingRuntime implementation,
                                 @Nonnull AttributePolicy policy) {
        super(implementation, policy, SpanCategory.KAFKA_PRODUCER, "KafkaProducerSpanBuilder");
    }

    @Override
    protected KafkaProducerSpanBuilder self() {
        return this;
    }

    @Override
    @Nonnull
    public KafkaProducerSpanBuilder destination(@Nonnull String topic) {
        putAttribute(SemconvKeys.MESSAGING_DESTINATION_NAME.getKey(), SpanAttributeValue.of(topic));
        return this;
    }

    @Override
    @Nonnull
    public KafkaProducerSpanBuilder operation(@Nonnull String operation) {
        putAttribute(SemconvKeys.MESSAGING_OPERATION.getKey(), SpanAttributeValue.of(operation));
        return this;
    }
}

final class KafkaConsumerSpanBuilderImpl extends AbstractKafkaSpanBuilder<KafkaConsumerSpanBuilder>
        implements KafkaConsumerSpanBuilder {

    KafkaConsumerSpanBuilderImpl(@Nonnull TracingRuntime implementation,
                                 @Nonnull AttributePolicy policy) {
        super(implementation, policy, SpanCategory.KAFKA_CONSUMER, "KafkaConsumerSpanBuilder");
    }

    @Override
    protected KafkaConsumerSpanBuilder self() {
        return this;
    }

    @Override
    @Nonnull
    public KafkaConsumerSpanBuilder destination(@Nonnull String topic) {
        putAttribute(SemconvKeys.MESSAGING_DESTINATION_NAME.getKey(), SpanAttributeValue.of(topic));
        return this;
    }

    @Override
    @Nonnull
    public KafkaConsumerSpanBuilder operation(@Nonnull String operation) {
        putAttribute(SemconvKeys.MESSAGING_OPERATION.getKey(), SpanAttributeValue.of(operation));
        return this;
    }

    @Override
    @Nonnull
    public KafkaBatchSpanBuilder batch(@Nonnull String destination) {
        Objects.requireNonNull(destination, "destination");
        if (destination.isBlank()) {
            throw new IllegalArgumentException("destination must not be blank");
        }
        return new KafkaBatchSpanBuilderImpl(implementation, policy, destination);
    }
}

final class KafkaBatchSpanBuilderImpl extends AbstractKafkaSpanBuilder<KafkaBatchSpanBuilder>
        implements KafkaBatchSpanBuilder {

    KafkaBatchSpanBuilderImpl(@Nonnull TracingRuntime implementation,
                              @Nonnull AttributePolicy policy,
                              @Nonnull String destination) {
        super(implementation, policy, SpanCategory.KAFKA_CONSUMER, "KafkaBatchSpanBuilder");
        putAttribute(SemconvKeys.MESSAGING_DESTINATION_NAME.getKey(), SpanAttributeValue.of(destination));
        putAttribute(SemconvKeys.MESSAGING_OPERATION.getKey(), SpanAttributeValue.of("process"));
    }

    @Override
    protected KafkaBatchSpanBuilder self() {
        return this;
    }
}
