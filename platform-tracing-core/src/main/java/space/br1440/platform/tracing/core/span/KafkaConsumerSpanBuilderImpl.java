package space.br1440.platform.tracing.core.span;

import io.opentelemetry.api.trace.Tracer;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.builder.KafkaConsumerSpanBuilder;
import space.br1440.platform.tracing.core.exception.ExceptionRecorder;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;

/** Policy-backed реализация {@link KafkaConsumerSpanBuilder} (KAFKA_CONSUMER, SpanKind CONSUMER). */
public final class KafkaConsumerSpanBuilderImpl extends AbstractTypedSpanBuilder<KafkaConsumerSpanBuilder>
        implements KafkaConsumerSpanBuilder {

    public KafkaConsumerSpanBuilderImpl(@Nonnull Tracer tracer, @Nonnull AttributePolicy policy,
                                        @Nonnull ExceptionRecorder exceptionRecorder) {
        super(tracer, policy, exceptionRecorder);
        // messaging.system обязателен контрактом; для этого builder'а он всегда "kafka".
        putAttribute(SemconvKeys.MESSAGING_SYSTEM, "kafka");
    }

    @Override
    protected SpanCategory category() {
        return SpanCategory.KAFKA_CONSUMER;
    }
}
