package space.br1440.platform.healthchecks.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.util.CollectionUtils;
import space.br1440.platform.healthchecks.kafka.exception.ConsumerGroupNotFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Health check для Kafka Consumer, основанный на offset-based forward progress —
 * сравнении committed offset consumer group с log-end offset партиций через read-only AdminClient запросы.
 *
 * <h3>Почему не {@code KafkaConsumer.poll()} в тестовый топик</h3>
 * <p>Создание временного consumer в health check — антипаттерн с серьёзными последствиями для production-кластера:
 * <ul>
 *   <li>Каждый вызов инициирует <b>consumer group rebalance</b>, блокируя
 *       обработку сообщений всеми членами группы на время rebalance-протокола</li>
 *   <li>Временная consumer group засоряет {@code __consumer_offsets} топик</li>
 *   <li>Создание нового {@code KafkaConsumer} требует
 *       {@code bootstrap.servers} — нарушает принцип единого AdminClient bean</li>
 *   <li>{@code poll()} потребляет реальные сообщения из тестового топика,
 *       создавая side effects при каждой проверке</li>
 * </ul>
 * PagerDuty прошли через три итерации health check реализаций, прежде чем
 * пришли к offset-based подходу без создания consumer
 * (<a href="https://www.pagerduty.com/eng/kafka-health-checks/">PagerDuty: Writing Intelligent Health Checks for Kafka Services</a>).
 *
 * <h3>Почему offset-based forward progress</h3>
 * <p>Подход использует два read-only AdminClient запроса:
 * <ol>
 *   <li>{@code listConsumerGroupOffsets} — возвращает committed offset
 *       для каждой партиции consumer group</li>
 *   <li>{@code listOffsets} — возвращает log-end offset для тех же партиций</li>
 * </ol>
 * Разница (lag) отражает реальное состояние consumer group без каких-либо
 * side effects на кластере. Этот подход внедрён в production компаниями
 * Cloudflare и PagerDuty, что привело к снижению ложных алертов на 50%
 * (<a href="https://blog.cloudflare.com/intelligent-automatic-restarts-for-unhealthy-kafka-consumers/">Cloudflare: Intelligent, automatic restarts for unhealthy Kafka consumers</a>).
 *
 * <h3>Что проверяет этот health check</h3>
 * <ul>
 *   <li><b>group_not_found</b> — consumer group не зарегистрирована в кластере
 *       (приложение ни разу не подключалось или неверный {@code groupId})</li>
 *   <li><b>reachable</b> — committed offsets найдены, lag посчитан успешно</li>
 *   <li><b>error</b> — AdminClient недоступен или кластер не отвечает</li>
 * </ul>
 *
 * <p><b>Важно:</b> {@code totalLag > 0} не означает нездоровье consumer —
 * lag является нормальным явлением при высокой нагрузке. Для мониторинга
 * роста lag используют Micrometer метрики или Confluent Health+, а не
 * health check endpoint
 * (<a href="https://www.pagerduty.com/eng/kafka-health-checks/">PagerDuty Kafka Health Checks</a>).
 *
 * @see KafkaProducerHealthCheck
 * @see <a href="https://www.pagerduty.com/eng/kafka-health-checks/">PagerDuty: Writing Intelligent Health Checks for Kafka Services</a>
 * @see <a href="https://blog.cloudflare.com/intelligent-automatic-restarts-for-unhealthy-kafka-consumers/">Cloudflare: Intelligent Kafka Consumer Restarts</a>
 * @see <a href="https://www.baeldung.com/java-kafka-consumer-lag">Baeldung: Monitor Consumer Lag in Apache Kafka</a>
 */
@Slf4j
@RequiredArgsConstructor
public class KafkaConsumerHealthCheck {

    private final KafkaHealthProperties properties;

    public void check(AdminClient adminClient, Map<String, Object> details) {
        KafkaHealthProperties.Consumer config = properties.getConsumer();
        Map<String, Object> consumerDetails = new LinkedHashMap<>();
        consumerDetails.put("groupId", config.getGroupId());

        try {
            ConsumerGroupLagSnapshot snapshot = resolveGroupLag(adminClient, config.getGroupId());
            consumerDetails.put("status", "reachable");
            consumerDetails.put("assignedPartitions", snapshot.partitionCount());
            consumerDetails.put("totalLag", snapshot.totalLag());
        } catch (ConsumerGroupNotFoundException e) {
            log.warn("Kafka consumer group not found: groupId={}", config.getGroupId());
            consumerDetails.put("status", "group_not_found");
            consumerDetails.put("error", e.getMessage());
        } catch (Exception e) {
            log.error("Kafka consumer health check failed: groupId={}", config.getGroupId(), e);
            consumerDetails.put("status", "error");
            consumerDetails.put("error", e.getMessage());
            consumerDetails.put("errorType", e.getClass().getSimpleName());
        }

        details.put("consumer", consumerDetails);
    }

    private ConsumerGroupLagSnapshot resolveGroupLag(AdminClient adminClient, String groupId) throws Exception {
        long timeoutSeconds = properties.getTimeout().getRequestTimeout().toSeconds();

        // 1. Получаем committed offsets consumer group
        Map<TopicPartition, OffsetAndMetadata> committedOffsets = adminClient
                .listConsumerGroupOffsets(groupId)
                .partitionsToOffsetAndMetadata()
                .get(timeoutSeconds, TimeUnit.SECONDS);

        if (CollectionUtils.isEmpty(committedOffsets)) {
            throw new ConsumerGroupNotFoundException(
                    "No committed offsets found for group: %s".formatted(groupId)
            );
        }

        // 2. Получаем log-end offsets для тех же партиций
        Map<TopicPartition, OffsetSpec> latestOffsetRequest = committedOffsets.keySet().stream()
                .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));

        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets = adminClient
                .listOffsets(latestOffsetRequest)
                .all()
                .get(timeoutSeconds, TimeUnit.SECONDS);

        // 3. Считаем суммарный lag: log-end-offset - committed-offset
        long totalLag = committedOffsets.entrySet().stream()
                .mapToLong(entry -> {
                    long committed = entry.getValue().offset();
                    long endOffset = endOffsets.getOrDefault(entry.getKey(),
                            new ListOffsetsResult.ListOffsetsResultInfo(committed, -1L, Optional.empty())
                    ).offset();
                    return Math.max(0L, endOffset - committed);
                })
                .sum();

        return new ConsumerGroupLagSnapshot(committedOffsets.size(), totalLag);
    }

    private record ConsumerGroupLagSnapshot(int partitionCount, long totalLag) {}
}
