package space.br1440.platform.metrics.kafka.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import space.br1440.platform.metrics.MetricTags;
import space.br1440.platform.metrics.PlatformMetrics;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Listener-уровневый интерцептор Spring Kafka для публикации платформенных consumer-метрик.
 *
 * <p>Метрики контракта SRE ADR публикуются <b>безусловно</b> (без флагов opt-out):</p>
 * <ul>
 *   <li>{@code platform_consumer_messages_total} — Counter с лейблом {@code result} ({@code success}/{@code failure});
 *       публикуется в {@link #success}/{@link #failure}.</li>
 *   <li>{@code platform_consumer_wait_seconds} — Timer от {@code record.timestamp()} до входа
 *       в listener-pipeline; публикуется в {@link #intercept}.</li>
 *   <li>{@code platform_consumer_lag_seconds} — Timer от {@code record.timestamp()} до конца
 *       обработки listener'ом; публикуется в {@link #success}/{@link #failure}.</li>
 * </ul>
 *
 * <p>Дополнительно (расширение контракта, не ломает дашборды SRE):</p>
 * <ul>
 *   <li>{@code platform_consumer_processing_seconds} — Timer длительности обработки записи;
 *       теги {@code outcome}, {@code exception_class}; публикуется в {@link #success}/{@link #failure}.</li>
 * </ul>
 *
 * <p><b>Семантический сдвиг {@code lag_seconds}:</b>
 * оригинальная реализация измеряла {@code record.timestamp() → onCommit} через
 * {@code ConsumerInterceptor}; данная реализация измеряет
 * {@code record.timestamp() → success/failure listener'а}. Для стандартной конфигурации
 * Spring Kafka (commit сразу после успешного listener'а) значения сопоставимы.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class ProcessRecordInterceptor implements RecordInterceptor<Object, Object> {

    private static final String START_NANO_HEADER = "__platform_metric_start_nano__";
    private static final String UNKNOWN = "unknown";

    private final Map<Consumer<?, ?>, String> clientIdCache = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<Consumer<?, ?>, String> groupIdCache = Collections.synchronizedMap(new IdentityHashMap<>());

    private final PlatformMetrics platformMetrics;

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                    Consumer<Object, Object> consumer) {
        try {
            byte[] payload = ByteBuffer.allocate(Long.BYTES).putLong(System.nanoTime()).array();
            record.headers().remove(START_NANO_HEADER);
            record.headers().add(START_NANO_HEADER, payload);
        } catch (Exception e) {
            log.debug("Не удалось записать startNanoTime в заголовок записи, topic={}, partition={}",
                    record.topic(), record.partition(), e);
        }

        try {
            String topic = record.topic();
            String clientId = resolveClientId(consumer);
            String group = resolveGroupId(consumer);

            long waitMs = System.currentTimeMillis() - record.timestamp();
            platformMetrics.recordConsumerWaitMillis(topic, clientId, group, waitMs);
        } catch (Exception e) {
            log.debug("Не удалось опубликовать метрики total/wait, topic={}, partition={}",
                    record.topic(), record.partition(), e);
        }

        return record;
    }

    @Override
    public void success(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        finish(record, consumer, MetricTags.OUTCOME_SUCCESS, null);
    }

    @Override
    public void failure(ConsumerRecord<Object, Object> record,
                        @Nullable Exception exception,
                        Consumer<Object, Object> consumer) {
        String exceptionClass = (exception != null)
                ? exception.getClass().getName()
                : MetricTags.EXCEPTION_NONE;

        finish(record, consumer, MetricTags.OUTCOME_ERROR, exceptionClass);
    }

    private void finish(ConsumerRecord<Object, Object> record,
                        Consumer<Object, Object> consumer,
                        String outcome,
                        @Nullable String exceptionClass) {
        String topic = record.topic();
        String clientId = resolveClientId(consumer);
        String group = resolveGroupId(consumer);

        try {
            platformMetrics.recordConsumerLagMillis(topic, clientId, group, record.timestamp());
        } catch (Exception e) {
            log.debug("Не удалось опубликовать метрику lag_seconds, topic={}, partition={}",
                    topic, record.partition(), e);
        }

        recordDuration(record, topic, clientId, group, outcome, exceptionClass);

        try {
            if (MetricTags.OUTCOME_SUCCESS.equals(outcome)) {
                platformMetrics.kafkaConsumerTotal(topic, clientId, group, MetricTags.RESULT_SUCCESS).increment();
            } else {
                platformMetrics.kafkaConsumerTotal(topic, clientId, group, MetricTags.RESULT_FAILURE).increment();
            }
        } catch (Exception e) {
            log.debug("Не удалось инкрементировать platform_consumer_messages_total, topic={}, partition={}",
                    topic, record.partition(), e);
        }
    }

    private void recordDuration(ConsumerRecord<Object, Object> record,
                                String topic,
                                String clientId,
                                String group,
                                String outcome,
                                @Nullable String exceptionClass) {
        try {
            Long startNano = readStartNano(record);
            if (startNano == null) {
                return;
            }

            long durationNanos = System.nanoTime() - startNano;
            platformMetrics.recordProcessDuration(topic, clientId, group, outcome, exceptionClass, durationNanos);
        } catch (Exception e) {
            log.debug("Не удалось записать длительность обработки записи, topic={}, partition={}",
                    topic, record.partition(), e);
        }
    }

    @Nullable
    private static Long readStartNano(ConsumerRecord<Object, Object> record) {
        Header header = record.headers().lastHeader(START_NANO_HEADER);
        if (header == null) {
            return null;
        }

        byte[] value = header.value();
        if (value == null || value.length != Long.BYTES) {
            return null;
        }

        return ByteBuffer.wrap(value).getLong();
    }

    private String resolveClientId(Consumer<?, ?> consumer) {
        String cached = clientIdCache.get(consumer);
        if (cached != null) {
            return cached;
        }

        String resolved = resolveClientIdUncached(consumer);
        clientIdCache.put(consumer, resolved);
        return resolved;
    }

    private static String resolveClientIdUncached(Consumer<?, ?> consumer) {
        try {
            for (Map.Entry<MetricName, ? extends Metric> entry : consumer.metrics().entrySet()) {
                String tagValue = entry.getKey().tags().get("client-id");
                if (StringUtils.hasText(tagValue)) {
                    return tagValue;
                }
            }

            return UNKNOWN;
        } catch (Exception e) {
            log.debug("Не удалось получить client.id из метрик Kafka consumer'а", e);
            return UNKNOWN;
        }
    }

    private String resolveGroupId(Consumer<?, ?> consumer) {
        String cached = groupIdCache.get(consumer);
        if (cached != null) {
            return cached;
        }

        String resolved = resolveGroupIdUncached(consumer);
        groupIdCache.put(consumer, resolved);
        return resolved;
    }

    private static String resolveGroupIdUncached(Consumer<?, ?> consumer) {
        try {
            String value = consumer.groupMetadata().groupId();
            return StringUtils.hasText(value) ? value : UNKNOWN;
        } catch (Exception e) {
            log.debug("Не удалось получить group.id (возможно, standalone consumer без группы)", e);
            return UNKNOWN;
        }
    }
}
