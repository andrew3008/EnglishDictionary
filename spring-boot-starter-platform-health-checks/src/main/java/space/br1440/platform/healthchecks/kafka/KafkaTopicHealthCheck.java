package space.br1440.platform.healthchecks.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartitionInfo;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Health check для Kafka топиков из {@code monitoredTopics}.
 *
 * <p>Проверяет два индустриальных критерия здоровья топика:
 * <ul>
 *   <li><b>Offline partitions</b> (leader = null) — критический сигнал: брокер с лидером
 *       недоступен, запись и чтение в эту партицию невозможны.
 *       Confluent рекомендует алертить при offline partitions > 0 как на P1-инцидент
 *       (<a href="https://docs.confluent.io/platform/current/kafka/post-deployment.html">Confluent: Best Practices for Kafka Production</a>)</li>
 *   <li><b>Under-replicated partitions</b> (ISR &lt; replicas) — предупреждение:
 *       данные не полностью реплицированы, устойчивость к отказам снижена
 *       (<a href="https://oneuptime.com/blog/post/2026-01-21-kafka-under-replicated-partitions/view">Kafka Under-Replicated Partitions</a>)</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class KafkaTopicHealthCheck {

    private final KafkaHealthProperties properties;

    public void check(AdminClient adminClient, Health.Builder builder, Map<String, Object> details) throws Exception {
        List<String> monitoredTopics = properties.getTopics().getMonitoredTopics();

        long timeoutSeconds = properties.getTimeout().getRequestTimeout().toSeconds();
        Map<String, TopicDescription> descriptions = adminClient
                .describeTopics(monitoredTopics)
                .allTopicNames()
                .get(timeoutSeconds, TimeUnit.SECONDS);

        Map<String, Object> topicsDetails = new LinkedHashMap<>();
        boolean hasOfflinePartitions = false;

        for (String topic : monitoredTopics) {
            TopicDescription description = descriptions.get(topic);
            if (description == null) {
                topicsDetails.put(topic, Map.of("status", TopicStatus.NOT_FOUND.toString()));
                hasOfflinePartitions = true;
                continue;
            }

            TopicHealthSnapshot snapshot = analyzePartitions(description.partitions());
            topicsDetails.put(topic, snapshot.toDetails());
            if (snapshot.status() == TopicStatus.OFFLINE_PARTITIONS) {
                hasOfflinePartitions = true;
            }
        }

        details.put("topics", topicsDetails);

        if (hasOfflinePartitions) {
            builder.status(Status.OUT_OF_SERVICE);
            log.warn("Kafka topic health check: offline partitions detected in monitored topics");
        }
    }

    private TopicHealthSnapshot analyzePartitions(List<TopicPartitionInfo> partitions) {
        int offlineCount = 0;
        int underReplicatedCount = 0;

        for (TopicPartitionInfo partition : partitions) {
            if (partition.leader() == null) {
                offlineCount++;
            } else if (partition.isr().size() < partition.replicas().size()) {
                underReplicatedCount++;
            }
        }

        return new TopicHealthSnapshot(partitions.size(), offlineCount, underReplicatedCount);
    }

    private record TopicHealthSnapshot(int totalPartitions,
                                       int offlinePartitions,
                                       int underReplicatedPartitions) {
        TopicStatus status() {
            if (offlinePartitions > 0) {
                return TopicStatus.OFFLINE_PARTITIONS;
            }

            if (underReplicatedPartitions > 0) {
                return TopicStatus.UNDER_REPLICATED;
            }

            return TopicStatus.HEALTHY;
        }

        Map<String, Object> toDetails() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("status", status().toString());
            details.put("totalPartitions", totalPartitions);

            if (offlinePartitions > 0) {
                details.put("offlinePartitions", offlinePartitions);
            }

            if (underReplicatedPartitions > 0) {
                details.put("underReplicatedPartitions", underReplicatedPartitions);
            }

            return details;
        }
    }

    public enum TopicStatus {
        HEALTHY,
        UNDER_REPLICATED,
        OFFLINE_PARTITIONS,
        NOT_FOUND;

        @Override
        public String toString() {
            return name().toLowerCase();
        }
    }
}
