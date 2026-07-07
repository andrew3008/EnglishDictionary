package space.br1440.platform.tracing.autoconfigure.kafka;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.kafka.annotation.KafkaListener;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.api.manual.KafkaBatchSpanBuilder;
import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Аспект для связывания (links) батчевых Kafka-вызовов с их исходными сообщениями.
 * <p>
 * OTel Java Agent 2.27 не создаёт per-record links для batch-listeners
 * (сохраняет только traceparent последнего сообщения как родительский).
 * Этот аспект создаёт отдельный KAFKA_CONSUMER span вокруг метода {@code @KafkaListener(batch="true")}
 * через v3 manual batch API, извлекает remote context из каждого {@link ConsumerRecord} через
 * настроенный OTel propagator и добавляет их как links к новому платформенному span'у.
 * <p>
 * Он <b>не мутирует</b> созданный агентом CONSUMER span, так как это хрупко.
 * <p>
 * <b>Destination resolution:</b> если все записи батча относятся к одному topic — destination
 * равен этому topic. Для multi-topic batch destination = {@code @KafkaListener.id()} при
 * непустом id, иначе имя advised-метода. Первый topic батча никогда не используется как
 * destination при нескольких distinct topics.
 */
@Aspect
public class KafkaBatchLinksAspect {

    private final OpenTelemetry openTelemetry;
    private final PlatformTracing platformTracing;

    public KafkaBatchLinksAspect(OpenTelemetry openTelemetry, PlatformTracing platformTracing) {
        this.openTelemetry = openTelemetry;
        this.platformTracing = platformTracing;
    }

    @Around("@annotation(kafkaListener) && args(records,..)")
    public Object linkBatchRecords(ProceedingJoinPoint pjp, KafkaListener kafkaListener,
                                   List<ConsumerRecord<?, ?>> records) throws Throwable {
        if (!"true".equalsIgnoreCase(kafkaListener.batch()) && !isBatchInferred(records)) {
            return pjp.proceed();
        }

        if (records == null || records.isEmpty()) {
            return pjp.proceed();
        }

        List<SpanLinkContext> links = new ArrayList<>();
        for (ConsumerRecord<?, ?> record : records) {
            SpanLinkContext link = extractLink(record);
            if (link != null) {
                links.add(link);
            }
        }

        String destination = resolveDestination(records, kafkaListener, pjp);

        KafkaBatchSpanBuilder builder = platformTracing.manual()
                .transport()
                .kafka()
                .consumer()
                .batch(destination)
                .root();
        if (!links.isEmpty()) {
            builder = builder.linkedTo(links.toArray(SpanLinkContext[]::new));
        }

        try (SpanHandle handle = builder.start()) {
            try {
                return pjp.proceed();
            } catch (Throwable t) {
                handle.recordException(t);
                throw t;
            }
        }
    }

    private String resolveDestination(List<ConsumerRecord<?, ?>> records,
                                      KafkaListener kafkaListener,
                                      ProceedingJoinPoint pjp) {
        Set<String> topics = new LinkedHashSet<>();
        for (ConsumerRecord<?, ?> record : records) {
            topics.add(record.topic());
        }
        if (topics.size() == 1) {
            return topics.iterator().next();
        }
        if (kafkaListener.id() != null && !kafkaListener.id().isBlank()) {
            return kafkaListener.id();
        }
        return pjp.getSignature().getName();
    }

    private SpanLinkContext extractLink(ConsumerRecord<?, ?> record) {
        Context extracted = openTelemetry.getPropagators().getTextMapPropagator()
                .extract(Context.root(), record, KafkaRecordGetter.INSTANCE);
        SpanContext spanContext = Span.fromContext(extracted).getSpanContext();
        if (!spanContext.isValid()) {
            return null;
        }
        return new SpanLinkContext(
                spanContext.getTraceId(),
                spanContext.getSpanId(),
                spanContext.getTraceFlags().asByte(),
                serializeTraceState(spanContext.getTraceState()));
    }

    private static String serializeTraceState(TraceState traceState) {
        if (traceState.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        traceState.forEach((key, value) -> {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(key).append('=').append(value);
        });
        return sb.toString();
    }

    private boolean isBatchInferred(List<ConsumerRecord<?, ?>> records) {
        return records != null;
    }

    private static final class KafkaRecordGetter implements TextMapGetter<ConsumerRecord<?, ?>> {
        static final KafkaRecordGetter INSTANCE = new KafkaRecordGetter();

        @Override
        public Iterable<String> keys(ConsumerRecord<?, ?> carrier) {
            return java.util.stream.StreamSupport.stream(carrier.headers().spliterator(), false)
                    .map(org.apache.kafka.common.header.Header::key)
                    .toList();
        }

        @Override
        public String get(ConsumerRecord<?, ?> carrier, String key) {
            org.apache.kafka.common.header.Header header = carrier.headers().lastHeader(key);
            if (header == null || header.value() == null) {
                return null;
            }
            return new String(header.value(), StandardCharsets.UTF_8);
        }
    }
}
