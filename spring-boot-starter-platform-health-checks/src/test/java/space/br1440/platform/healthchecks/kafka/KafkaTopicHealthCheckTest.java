package space.br1440.platform.healthchecks.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KafkaTopicHealthCheck")
class KafkaTopicHealthCheckTest {

    private static final String TOPIC_A = "payments";
    private static final String TOPIC_B = "orders";

    private static final Node BROKER_0 = new Node(0, "broker-0", 9092);
    private static final Node BROKER_1 = new Node(1, "broker-1", 9092);
    private static final Node BROKER_2 = new Node(2, "broker-2", 9092);

    @Mock
    private AdminClient adminClient;

    private KafkaHealthProperties properties;
    private KafkaTopicHealthCheck check;

    @BeforeEach
    void setUp() {
        properties = new KafkaHealthProperties();
        properties.getTopics().setEnabled(true).setMonitoredTopics(List.of(TOPIC_A));
        check = new KafkaTopicHealthCheck(properties);
    }

    @Nested
    @DisplayName("healthy topic")
    class HealthyTopic {

        @Test
        @DisplayName("status=healthy когда все партиции имеют лидера и ISR == replicas")
        void shouldReportHealthyWhenAllPartitionsFullyReplicated() throws Exception {
            // Given
            TopicPartitionInfo p0 = partition(0, BROKER_0, List.of(BROKER_0, BROKER_1), List.of(BROKER_0, BROKER_1));
            TopicPartitionInfo p1 = partition(1, BROKER_1, List.of(BROKER_0, BROKER_1), List.of(BROKER_0, BROKER_1));
            mockDescribeTopics(Map.of(TOPIC_A, topicDescription(TOPIC_A, List.of(p0, p1))));

            // When
            Map<String, Object> result = topicDetails(TOPIC_A, checkTopics());

            // Then
            assertThat(result)
                    .containsEntry("status", "healthy")
                    .containsEntry("totalPartitions", 2)
                    .doesNotContainKeys("offlinePartitions", "underReplicatedPartitions");
        }

        @Test
        @DisplayName("builder остаётся UP когда все топики healthy")
        void shouldNotChangeBuilderStatusWhenAllHealthy() throws Exception {
            // Given
            TopicPartitionInfo p0 = partition(0, BROKER_0, List.of(BROKER_0), List.of(BROKER_0));
            mockDescribeTopics(Map.of(TOPIC_A, topicDescription(TOPIC_A, List.of(p0))));
            Health.Builder builder = Health.up();

            // When
            check.check(adminClient, builder, new HashMap<>());

            // Then
            assertThat(builder.build().getStatus()).isEqualTo(Status.UP);
        }

        @Test
        @DisplayName("single-partition single-replica топик считается healthy")
        void shouldReportHealthyForSinglePartitionSingleReplica() throws Exception {
            // Given
            TopicPartitionInfo p0 = partition(0, BROKER_0, List.of(BROKER_0), List.of(BROKER_0));
            mockDescribeTopics(Map.of(TOPIC_A, topicDescription(TOPIC_A, List.of(p0))));

            // When
            Map<String, Object> result = topicDetails(TOPIC_A, checkTopics());

            // Then
            assertThat(result).containsEntry("status", "healthy");
        }
    }

    @Nested
    @DisplayName("offline partitions")
    class OfflinePartitions {

        @Test
        @DisplayName("status=offline_partitions когда хотя бы одна партиция без лидера")
        void shouldReportOfflineWhenOnePartitionHasNoLeader() throws Exception {
            // Given
            TopicPartitionInfo online  = partition(0, BROKER_0, List.of(BROKER_0, BROKER_1), List.of(BROKER_0, BROKER_1));
            TopicPartitionInfo offline = partition(1, null,     List.of(BROKER_0, BROKER_1), List.of());
            mockDescribeTopics(Map.of(TOPIC_A, topicDescription(TOPIC_A, List.of(online, offline))));

            // When
            Map<String, Object> result = topicDetails(TOPIC_A, checkTopics());

            // Then
            assertThat(result)
                    .containsEntry("status", "offline_partitions")
                    .containsEntry("offlinePartitions", 1)
                    .containsEntry("totalPartitions", 2);
        }

        @Test
        @DisplayName("offlinePartitions считает все офлайн-партиции")
        void shouldCountAllOfflinePartitions() throws Exception {
            // Given
            TopicPartitionInfo p0 = partition(0, null, List.of(BROKER_0), List.of());
            TopicPartitionInfo p1 = partition(1, null, List.of(BROKER_1), List.of());
            TopicPartitionInfo p2 = partition(2, BROKER_2, List.of(BROKER_2), List.of(BROKER_2));
            mockDescribeTopics(Map.of(TOPIC_A, topicDescription(TOPIC_A, List.of(p0, p1, p2))));

            // When
            Map<String, Object> result = topicDetails(TOPIC_A, checkTopics());

            // Then
            assertThat(result).containsEntry("offlinePartitions", 2);
        }

        @Test
        @DisplayName("builder переходит в OUT_OF_SERVICE при offline partitions")
        void shouldSetBuilderOutOfServiceWhenOfflinePartitions() throws Exception {
            // Given
            TopicPartitionInfo offline = partition(0, null, List.of(BROKER_0), List.of());
            mockDescribeTopics(Map.of(TOPIC_A, topicDescription(TOPIC_A, List.of(offline))));
            Health.Builder builder = Health.up();

            // When
            check.check(adminClient, builder, new HashMap<>());

            // Then
            assertThat(builder.build().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        }

        @Test
        @DisplayName("offline топик не содержит underReplicatedPartitions в details")
        void shouldNotIncludeUnderReplicatedWhenOffline() throws Exception {
            // Given
            TopicPartitionInfo offline = partition(0, null, List.of(BROKER_0, BROKER_1), List.of());
            mockDescribeTopics(Map.of(TOPIC_A, topicDescription(TOPIC_A, List.of(offline))));

            // When
            Map<String, Object> result = topicDetails(TOPIC_A, checkTopics());

            // Then — OFFLINE_PARTITIONS имеет приоритет над UNDER_REPLICATED
            assertThat(result).doesNotContainKey("underReplicatedPartitions");
        }
    }

    @Nested
    @DisplayName("under-replicated partitions")
    class UnderReplicatedPartitions {

        @Test
        @DisplayName("status=under_replicated когда ISR < replicas и лидер присутствует")
        void shouldReportUnderReplicatedWhenIsrBelowReplicaCount() throws Exception {
            // Given
            TopicPartitionInfo underReplicated = partition(0, BROKER_0, List.of(BROKER_0, BROKER_1), List.of(BROKER_0));
            mockDescribeTopics(Map.of(TOPIC_A, topicDescription(TOPIC_A, List.of(underReplicated))));

            // When
            Map<String, Object> result = topicDetails(TOPIC_A, checkTopics());

            // Then
            assertThat(result)
                    .containsEntry("status", "under_replicated")
                    .containsEntry("underReplicatedPartitions", 1)
                    .containsEntry("totalPartitions", 1);
        }

        @Test
        @DisplayName("builder остаётся UP при under-replicated (не является offline)")
        void shouldNotChangeBuilderStatusWhenUnderReplicated() throws Exception {
            // Given
            TopicPartitionInfo underReplicated = partition(0, BROKER_0, List.of(BROKER_0, BROKER_1), List.of(BROKER_0));
            mockDescribeTopics(Map.of(TOPIC_A, topicDescription(TOPIC_A, List.of(underReplicated))));
            Health.Builder builder = Health.up();

            // When
            check.check(adminClient, builder, new HashMap<>());

            // Then
            assertThat(builder.build().getStatus()).isEqualTo(Status.UP);
        }

        @Test
        @DisplayName("underReplicatedPartitions считает все under-replicated партиции")
        void shouldCountAllUnderReplicatedPartitions() throws Exception {
            // Given
            TopicPartitionInfo ur0 = partition(0, BROKER_0, List.of(BROKER_0, BROKER_1), List.of(BROKER_0));
            TopicPartitionInfo ur1 = partition(1, BROKER_1, List.of(BROKER_1, BROKER_2), List.of(BROKER_1));
            TopicPartitionInfo ok2 = partition(2, BROKER_2, List.of(BROKER_2), List.of(BROKER_2));
            mockDescribeTopics(Map.of(TOPIC_A, topicDescription(TOPIC_A, List.of(ur0, ur1, ok2))));

            // When
            Map<String, Object> result = topicDetails(TOPIC_A, checkTopics());

            // Then
            assertThat(result)
                    .containsEntry("underReplicatedPartitions", 2)
                    .containsEntry("totalPartitions", 3);
        }

        @Test
        @DisplayName("under-replicated топик не содержит offlinePartitions в details")
        void shouldNotIncludeOfflinePartitionsWhenUnderReplicated() throws Exception {
            // Given
            TopicPartitionInfo underReplicated = partition(0, BROKER_0, List.of(BROKER_0, BROKER_1), List.of(BROKER_0));
            mockDescribeTopics(Map.of(TOPIC_A, topicDescription(TOPIC_A, List.of(underReplicated))));

            // When
            Map<String, Object> result = topicDetails(TOPIC_A, checkTopics());

            // Then
            assertThat(result).doesNotContainKey("offlinePartitions");
        }
    }

    @Nested
    @DisplayName("topic not found")
    class TopicNotFound {

        @Test
        @DisplayName("status=not_found когда топик отсутствует в ответе describeTopics")
        void shouldReportNotFoundWhenTopicAbsentInDescriptions() throws Exception {
            // Given — describeTopics не вернул описание для TOPIC_A
            mockDescribeTopics(Map.of());

            // When
            Map<String, Object> result = topicDetails(TOPIC_A, checkTopics());

            // Then
            assertThat(result).containsEntry("status", "not_found");
        }

        @Test
        @DisplayName("builder переходит в OUT_OF_SERVICE когда топик не найден")
        void shouldSetBuilderOutOfServiceWhenTopicNotFound() throws Exception {
            // Given
            mockDescribeTopics(Map.of());
            Health.Builder builder = Health.up();

            // When
            check.check(adminClient, builder, new HashMap<>());

            // Then
            assertThat(builder.build().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        }
    }

    @Nested
    @DisplayName("multiple monitored topics")
    class MultipleTopics {

        @BeforeEach
        void setTwoTopics() {
            properties.getTopics().setMonitoredTopics(List.of(TOPIC_A, TOPIC_B));
        }

        @Test
        @DisplayName("details содержит записи для всех monitored topics")
        void shouldIncludeDetailsForAllMonitoredTopics() throws Exception {
            // Given
            TopicPartitionInfo pA = partition(0, BROKER_0, List.of(BROKER_0), List.of(BROKER_0));
            TopicPartitionInfo pB = partition(0, BROKER_1, List.of(BROKER_1), List.of(BROKER_1));
            mockDescribeTopics(Map.of(
                    TOPIC_A, topicDescription(TOPIC_A, List.of(pA)),
                    TOPIC_B, topicDescription(TOPIC_B, List.of(pB))
            ));

            // When
            Map<String, Object> topics = checkTopics();

            // Then
            assertThat(topics).containsKeys(TOPIC_A, TOPIC_B);
        }

        @Test
        @DisplayName("один offline топик переводит builder в OUT_OF_SERVICE даже если другой healthy")
        void shouldSetBuilderOutOfServiceWhenOneTopicOffline() throws Exception {
            // Given
            TopicPartitionInfo healthy = partition(0, BROKER_0, List.of(BROKER_0), List.of(BROKER_0));
            TopicPartitionInfo offline = partition(0, null,     List.of(BROKER_1), List.of());
            mockDescribeTopics(Map.of(
                    TOPIC_A, topicDescription(TOPIC_A, List.of(healthy)),
                    TOPIC_B, topicDescription(TOPIC_B, List.of(offline))
            ));
            Health.Builder builder = Health.up();

            // When
            check.check(adminClient, builder, new HashMap<>());

            // Then
            assertThat(builder.build().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        }

        @Test
        @DisplayName("каждый топик имеет независимый статус в details")
        void shouldReportIndependentStatusPerTopic() throws Exception {
            // Given
            TopicPartitionInfo healthy      = partition(0, BROKER_0, List.of(BROKER_0), List.of(BROKER_0));
            TopicPartitionInfo underReplicated = partition(0, BROKER_1, List.of(BROKER_1, BROKER_2), List.of(BROKER_1));
            mockDescribeTopics(Map.of(
                    TOPIC_A, topicDescription(TOPIC_A, List.of(healthy)),
                    TOPIC_B, topicDescription(TOPIC_B, List.of(underReplicated))
            ));

            // When
            Map<String, Object> topics = checkTopics();

            // Then
            assertThat(topicDetails(TOPIC_A, topics)).containsEntry("status", "healthy");
            assertThat(topicDetails(TOPIC_B, topics)).containsEntry("status", "under_replicated");
        }

        @Test
        @DisplayName("один топик not_found и один healthy — оба попадают в details")
        void shouldReportNotFoundAndHealthyIndependently() throws Exception {
            // Given
            TopicPartitionInfo healthy = partition(0, BROKER_0, List.of(BROKER_0), List.of(BROKER_0));
            mockDescribeTopics(Map.of(TOPIC_A, topicDescription(TOPIC_A, List.of(healthy))));

            // When
            Map<String, Object> topics = checkTopics();

            // Then
            assertThat(topicDetails(TOPIC_A, topics)).containsEntry("status", "healthy");
            assertThat(topicDetails(TOPIC_B, topics)).containsEntry("status", "not_found");
        }
    }

    @Nested
    @DisplayName("AdminClient failure")
    class AdminClientFailure {

        @Test
        @DisplayName("бросает ExecutionException когда describeTopics завершается с ошибкой")
        void shouldPropagateExceptionWhenDescribeTopicsFails() throws Exception {
            // Given
            DescribeTopicsResult result = mock(DescribeTopicsResult.class);
            KafkaFuture<Map<String, TopicDescription>> future = failedFuture(
                    new ExecutionException("Leader not available", new RuntimeException())
            );
            when(result.allTopicNames()).thenReturn(future);
            when(adminClient.describeTopics(anyCollection())).thenReturn(result);

            Health.Builder builder = Health.up();
            Map<String, Object> details = new HashMap<>();

            // When / Then — исключение propagates наружу (KafkaHealthIndicator ловит его в checkTopics)
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> check.check(adminClient, builder, details)
            ).isInstanceOf(ExecutionException.class);
        }

        @Test
        @DisplayName("бросает TimeoutException когда describeTopics не отвечает вовремя")
        void shouldPropagateTimeoutExceptionWhenDescribeTopicsTimesOut() throws Exception {
            // Given
            DescribeTopicsResult result = mock(DescribeTopicsResult.class);
            KafkaFuture<Map<String, TopicDescription>> future = failedFuture(
                    new TimeoutException("Request timed out")
            );
            when(result.allTopicNames()).thenReturn(future);
            when(adminClient.describeTopics(anyCollection())).thenReturn(result);

            // When / Then
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> check.check(adminClient, Health.up(), new HashMap<>())
            ).isInstanceOf(TimeoutException.class);
        }
    }

    @Nested
    @DisplayName("details structure")
    class DetailsStructure {

        @Test
        @DisplayName("результат помещается под ключ 'topics' в переданный map")
        void shouldPopulateTopicsKeyInGivenMap() throws Exception {
            // Given
            TopicPartitionInfo p0 = partition(0, BROKER_0, List.of(BROKER_0), List.of(BROKER_0));
            mockDescribeTopics(Map.of(TOPIC_A, topicDescription(TOPIC_A, List.of(p0))));
            Map<String, Object> outer = new HashMap<>();

            // When
            check.check(adminClient, Health.up(), outer);

            // Then
            assertThat(outer).containsKey("topics");
            assertThat(outer.get("topics")).isInstanceOf(Map.class);
        }

        @Test
        @DisplayName("не затрагивает другие ключи в переданном map")
        void shouldNotModifyOtherKeysInGivenMap() throws Exception {
            // Given
            TopicPartitionInfo p0 = partition(0, BROKER_0, List.of(BROKER_0), List.of(BROKER_0));
            mockDescribeTopics(Map.of(TOPIC_A, topicDescription(TOPIC_A, List.of(p0))));
            Map<String, Object> outer = new HashMap<>();
            outer.put("brokerCount", 3);

            // When
            check.check(adminClient, Health.up(), outer);

            // Then
            assertThat(outer).containsEntry("brokerCount", 3);
        }

        @Test
        @DisplayName("healthy details содержит только status и totalPartitions")
        void shouldContainOnlyStatusAndTotalPartitionsForHealthyTopic() throws Exception {
            // Given
            TopicPartitionInfo p0 = partition(0, BROKER_0, List.of(BROKER_0), List.of(BROKER_0));
            mockDescribeTopics(Map.of(TOPIC_A, topicDescription(TOPIC_A, List.of(p0))));

            // When
            Map<String, Object> result = topicDetails(TOPIC_A, checkTopics());

            // Then
            assertThat(result).containsOnlyKeys("status", "totalPartitions");
        }

        @Test
        @DisplayName("порядок ключей: status → totalPartitions (LinkedHashMap — порядок вставки)")
        void shouldReturnDetailsInInsertionOrder() throws Exception {
            // Given
            TopicPartitionInfo p0 = partition(0, BROKER_0, List.of(BROKER_0), List.of(BROKER_0));
            mockDescribeTopics(Map.of(TOPIC_A, topicDescription(TOPIC_A, List.of(p0))));

            // When
            List<String> keys = new ArrayList<>(topicDetails(TOPIC_A, checkTopics()).keySet());

            // Then
            assertThat(keys.subList(0, 2)).containsExactly("status", "totalPartitions");
        }
    }

    private Map<String, Object> checkTopics() throws Exception {
        Map<String, Object> outer = new HashMap<>();
        check.check(adminClient, Health.up(), outer);
        return topicsMap(outer);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> topicsMap(Map<String, Object> outer) {
        return (Map<String, Object>) outer.get("topics");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> topicDetails(String topic, Map<String, Object> topics) {
        return (Map<String, Object>) topics.get(topic);
    }

    private void mockDescribeTopics(Map<String, TopicDescription> descriptions) {
        DescribeTopicsResult result = mock(DescribeTopicsResult.class);
        when(result.allTopicNames()).thenReturn(KafkaFuture.completedFuture(descriptions));
        when(adminClient.describeTopics(anyCollection())).thenReturn(result);
    }

    private static TopicDescription topicDescription(String name, List<TopicPartitionInfo> partitions) {
        return new TopicDescription(name, false, partitions);
    }

    private static TopicPartitionInfo partition(int id, Node leader, List<Node> replicas, List<Node> isr) {
        return new TopicPartitionInfo(id, leader, replicas, isr);
    }

    @SuppressWarnings("unchecked")
    private static <T> KafkaFuture<T> failedFuture(Exception cause) throws Exception {
        KafkaFuture<T> future = mock(KafkaFuture.class);
        when(future.get(anyLong(), any())).thenThrow(cause);
        return future;
    }
}
