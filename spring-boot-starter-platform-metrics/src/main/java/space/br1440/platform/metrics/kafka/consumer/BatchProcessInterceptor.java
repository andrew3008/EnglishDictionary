package space.br1440.platform.metrics.kafka.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.listener.BatchInterceptor;
import org.springframework.lang.Nullable;
import space.br1440.platform.metrics.MetricTags;
import space.br1440.platform.metrics.PlatformMetrics;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * {@link BatchInterceptor} платформенных метрик для listener'ов с {@code @KafkaListener(batch = true)}.
 *
 * <p>Публикует:</p>
 * <ul>
 *   <li>{@code platform_consumer_batch_processing_seconds} — Timer длительности обработки
 *       batch'а; теги {@code outcome}, {@code exception_class}, {@code topic},
 *       {@code client_id}, {@code consumer_group};</li>
 *   <li>{@code platform_consumer_batch_messages_total} — DistributionSummary размера
 *       batch'а в записях (baseUnit = "records");</li>
 *   <li>{@code platform_consumer_messages_total} с {@code result=failure} — по одному инкременту
 *       на топик, присутствующий в batch'е, при ошибке обработки.</li>
 * </ul>
 *
 * <h3>Мульти-topic batch</h3>
 * <p>Если один batch содержит записи из нескольких топиков, одна и та же
 * {@code durationNanos} публикуется отдельно для каждого топика (разные time-series
 * с тегом {@code topic}). Это намеренно: каждая серия отражает длительность
 * обработки batch'а с точки зрения данного топика.
 * <b>Не следует суммировать</b> эту latency по всем топикам в PromQL —
 * сумма завысит вклад одного реального прохода listener'а.</p>
 *
 * <h3>ThreadLocal для времени старта</h3>
 * <p>В отличие от {@link ProcessRecordInterceptor}, для batch-сценария стартовое время
 * сохраняется в {@link ThreadLocal}: {@code intercept} и {@code success}/{@code failure}
 * вызываются Spring Kafka в одном потоке listener-контейнера.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class BatchProcessInterceptor implements BatchInterceptor<Object, Object> {

    private static final String UNKNOWN = "unknown";

    private final ThreadLocal<Long> batchStartNano = new ThreadLocal<>();

    private final Map<Consumer<?, ?>, String> clientIdCache = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<Consumer<?, ?>, String> groupIdCache = Collections.synchronizedMap(new IdentityHashMap<>());

    private final PlatformMetrics platformMetrics;

    @Override
    public ConsumerRecords<Object, Object> intercept(ConsumerRecords<Object, Object> records,
                                                     Consumer<Object, Object> consumer) {
        batchStartNano.set(System.nanoTime());
        return records;
    }

    @Override
    public void success(ConsumerRecords<Object, Object> records, Consumer<Object, Object> consumer) {
        Map<String, Integer> sizesByTopic = batchSizeByTopic(records);
        finish(consumer, MetricTags.OUTCOME_SUCCESS, null, sizesByTopic);
    }

    @Override
    public void failure(ConsumerRecords<Object, Object> records, Exception exception,
                        Consumer<Object, Object> consumer) {
        String exceptionClass = exception.getClass().getName();
        Map<String, Integer> sizesByTopic = batchSizeByTopic(records);
        finish(consumer, MetricTags.OUTCOME_ERROR, exceptionClass, sizesByTopic);

        try {
            String clientId = resolveClientId(consumer);
            String group = resolveGroupId(consumer);
            for (String topic : sizesByTopic.keySet()) {
                platformMetrics.kafkaConsumerTotal(topic, clientId, group, MetricTags.RESULT_FAILURE).increment();
            }
        } catch (Exception e) {
            log.debug("Failed to increment batch error counter", e);
        }
    }

    private void finish(Consumer<Object, Object> consumer,
                        String outcome,
                        @Nullable String exceptionClass,
                        Map<String, Integer> batchSizeByTopic) {
        try {
            Long start = batchStartNano.get();
            if (start == null) {
                return;
            }

            long durationNanos = System.nanoTime() - start;

            String clientId = resolveClientId(consumer);
            String group = resolveGroupId(consumer);

            for (Map.Entry<String, Integer> e : batchSizeByTopic.entrySet()) {
                String topic = e.getKey();
                int size = e.getValue();

                platformMetrics.recordBatchProcessDuration(topic, clientId, group, outcome, exceptionClass, durationNanos);
                platformMetrics.recordBatchSize(topic, clientId, group, size);
            }
        } catch (Exception e) {
            log.debug("Failed to record batch metrics", e);
        } finally {
            batchStartNano.remove();
        }
    }

    private static Map<String, Integer> batchSizeByTopic(ConsumerRecords<Object, Object> records) {
        Map<String, Integer> sizes = new HashMap<>();
        for (TopicPartition tp : records.partitions()) {
            sizes.merge(tp.topic(), records.records(tp).size(), Integer::sum);
        }

        return sizes;
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
                if (tagValue != null && !tagValue.isEmpty()) {
                    return tagValue;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to retrieve client.id from Kafka consumer metrics", e);
        }

        return UNKNOWN;
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
            return (value != null && !value.isEmpty()) ? value : UNKNOWN;
        } catch (Exception e) {
            log.debug("Failed to retrieve group.id; consumer may be running without a group", e);
            return UNKNOWN;
        }
    }
}