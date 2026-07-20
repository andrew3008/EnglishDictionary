package space.br1440.platform.tracing.autoconfigure.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.kafka.annotation.KafkaListener;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.span.builder.KafkaBatchSpanBuilder;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Аспект для связывания (links) батчевых Kafka-вызовов с их исходными сообщениями.
 * <p>
 * OTel Java Agent 2.27 не создаёт per-record links для batch-listeners
 * (сохраняет только traceparent последнего сообщения как родительский).
 * Этот аспект создаёт отдельный KAFKA_CONSUMER span вокруг метода {@code @KafkaListener(batch="true")}
 * через v3 manual batch API, извлекает W3C {@code traceparent}/{@code tracestate} из каждого
 * {@link ConsumerRecord} и добавляет их как links к новому платформенному span'у.
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

    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("[0-9a-f]{16}");
    private static final Pattern TRACE_FLAGS = Pattern.compile("[0-9a-f]{2}");

    private final TraceOperations traceOperations;

    public KafkaBatchLinksAspect(TraceOperations traceOperations) {
        this.traceOperations = traceOperations;
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

        List<RemoteSpanLink> links = new ArrayList<>();
        for (ConsumerRecord<?, ?> record : records) {
            RemoteSpanLink link = extractLink(record);
            if (link != null) {
                links.add(link);
            }
        }

        String destination = resolveDestination(records, kafkaListener, pjp);

        KafkaBatchSpanBuilder builder = traceOperations.spans()
                .transport()
                .kafka()
                .consumer()
                .batch(destination)
                .root();
        if (!links.isEmpty()) {
            builder = builder.linkedTo(links.toArray(RemoteSpanLink[]::new));
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

    private RemoteSpanLink extractLink(ConsumerRecord<?, ?> record) {
        String traceparent = header(record, "traceparent");
        if (traceparent == null) {
            return null;
        }
        String normalized = traceparent.toLowerCase(java.util.Locale.ROOT);
        String[] parts = normalized.split("-", -1);
        if (parts.length != 4
                || !"00".equals(parts[0])
                || !TRACE_ID.matcher(parts[1]).matches()
                || !SPAN_ID.matcher(parts[2]).matches()
                || !TRACE_FLAGS.matcher(parts[3]).matches()
                || isAllZero(parts[1])
                || isAllZero(parts[2])) {
            return null;
        }
        return new RemoteSpanLink(parts[1], parts[2], (byte) Integer.parseInt(parts[3], 16),
                header(record, "tracestate"));
    }

    private static boolean isAllZero(String value) {
        return value.chars().allMatch(character -> character == '0');
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        org.apache.kafka.common.header.Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null) {
            return null;
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private boolean isBatchInferred(List<ConsumerRecord<?, ?>> records) {
        return records != null;
    }

}
