package space.br1440.platform.healthchecks.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DisplayName("KafkaHealthIndicator: integration")
class KafkaHealthIntegrationTest {

    @Container
    static final ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.7.7")
    ).withStartupTimeout(Duration.ofMinutes(2));

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            classes = KafkaHealthIntegrationTest.BaseTestConfig.class
    )
    @EnableAutoConfiguration
    @DisplayName("Cluster & Producer")
    class ClusterAndProducer {

        @DynamicPropertySource
        static void configureProperties(DynamicPropertyRegistry registry) {
            baseKafkaProperties(registry);
            registry.add("platform.health.kafka.producer.enabled", () -> "true");
            registry.add("platform.health.kafka.consumer.enabled", () -> "false");
        }

        @Autowired
        private KafkaHealthIndicator healthIndicator;

        @Autowired
        private KafkaTemplate<String, String> kafkaTemplate;

        @Test
        @DisplayName("Kafka контейнер запущен и beans autowired")
        void shouldStartKafkaContainerAndAutowireBeans() {
            // Given / When / Then
            assertThat(kafka.isRunning()).isTrue();
            assertThat(kafkaTemplate).isNotNull();
            assertThat(healthIndicator).isNotNull();
        }

        @Test
        @DisplayName("Status=UP когда кластер доступен")
        void shouldReturnUpWhenClusterAccessible() {
            // When
            Health health = healthIndicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .containsKey("brokerCount")
                    .containsKey("clusterId")
                    .doesNotContainKey("clusterWarning");
        }

        @Test
        @DisplayName("brokerCount=1 для single-node Testcontainers кластера")
        void shouldReportOneBroker() {
            // When
            Health health = healthIndicator.health();

            // Then
            assertThat(health.getDetails().get("brokerCount"))
                    .isInstanceOf(Integer.class)
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("clusterId — непустая строка, не 'unknown'")
        void shouldReportRealClusterId() {
            // When
            Health health = healthIndicator.health();

            // Then
            assertThat(health.getDetails().get("clusterId"))
                    .isInstanceOf(String.class)
                    .asString()
                    .isNotBlank()
                    .isNotEqualTo("unknown");
        }

        @Test
        @DisplayName("Status=OUT_OF_SERVICE когда minBrokerCount превышает реальное число брокеров")
        void shouldReturnOutOfServiceWhenMinBrokerCountExceedsActual(@Autowired KafkaHealthProperties properties) {
            // Given
            int original = properties.getCluster().getMinBrokerCount();
            try {
                properties.getCluster().setMinBrokerCount(99);

                // When
                Health health = healthIndicator.health();

                // Then
                assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
                assertThat(health.getDetails()).containsKey("clusterWarning");
            } finally {
                properties.getCluster().setMinBrokerCount(original);
            }
        }

        @Test
        @DisplayName("producer.status=reachable когда есть активные TCP-соединения с брокером")
        void shouldReportProducerReachable() {
            // Given — инициализируем соединение producer через send()
            kafkaTemplate.send("health-probe-warmup", "warmup");

            // When
            Health health = healthIndicator.health();

            // Then
            assertThat(health.getDetails()).containsKey("producer");
            Map<String, Object> producer = producerDetails(health);
            assertThat(producer).containsEntry("status", "reachable");
            assertThat(producer.get("connectionCount"))
                    .isInstanceOf(Long.class)
                    .asInstanceOf(InstanceOfAssertFactories.LONG)
                    .isGreaterThan(0L);
        }

        @Test
        @DisplayName("details содержит ключи в предсказуемом порядке (LinkedHashMap)")
        void shouldReturnDetailsInPredictableOrder() {
            // When
            Health health = healthIndicator.health();
            List<String> keys = new ArrayList<>(health.getDetails().keySet());

            // Then
            assertThat(keys.subList(0, 2)).containsExactly("brokerCount", "clusterId");
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            classes = KafkaHealthIntegrationTest.BaseTestConfig.class
    )
    @EnableAutoConfiguration
    @DisplayName("Consumer: offset-based forward progress")
    class ConsumerCheck {

        private static final String CONSUMER_TOPIC = "health-consumer-integration-test";
        private static final String CONSUMER_GROUP = "health-consumer-integration-group";
        private static final int PARTITION_COUNT = 1;
        private static final int REPLICATION_FACTOR = 1;
        private static final int MESSAGE_COUNT = 3;

        @DynamicPropertySource
        static void configureProperties(DynamicPropertyRegistry registry) {
            baseKafkaProperties(registry);
            registry.add("platform.health.kafka.producer.enabled", () -> "false");
            registry.add("platform.health.kafka.consumer.enabled", () -> "true");
            registry.add("platform.health.kafka.consumer.group-id", () -> CONSUMER_GROUP);
        }

        @Autowired
        private KafkaHealthIndicator healthIndicator;

        @Autowired
        private KafkaTemplate<String, String> kafkaTemplate;

        @Autowired
        private AdminClient adminClient;

        @Test
        @DisplayName("consumer.status=group_not_found когда consumer group ещё не зарегистрирована в кластере")
        void shouldReportGroupNotFoundWhenNoCommittedOffsets() {
            // Given — используем заведомо несуществующую группу
            String unknownGroup = "health-unknown-group-" + UUID.randomUUID();
            String originalGroupId = withTemporaryGroupId(unknownGroup);
            try {
                // When
                Health health = healthIndicator.health();

                // Then
                assertThat(health.getDetails()).containsKey("consumer");
                Map<String, Object> consumer = consumerDetails(health);
                assertThat(consumer)
                        .containsEntry("status", "group_not_found")
                        .containsEntry("groupId", unknownGroup)
                        .containsKey("error");
            } finally {
                restoreGroupId(originalGroupId);
            }
        }

        @Test
        @DisplayName("consumer.status=reachable когда committed offsets существуют (lag=0)")
        void shouldReportConsumerReachableWithZeroLag() throws Exception {
            // Given — создаём топик, отправляем и полностью консьюмим сообщения
            createTopicIfAbsent(adminClient, CONSUMER_TOPIC, PARTITION_COUNT, REPLICATION_FACTOR);
            produceMessages(CONSUMER_TOPIC, MESSAGE_COUNT);
            consumeAndCommit(CONSUMER_TOPIC, CONSUMER_GROUP, MESSAGE_COUNT);

            // When
            Health health = healthIndicator.health();

            // Then
            assertThat(health.getDetails()).containsKey("consumer");
            Map<String, Object> consumer = consumerDetails(health);
            assertThat(consumer)
                    .containsEntry("status", "reachable")
                    .containsEntry("groupId", CONSUMER_GROUP);
            assertThat(consumer.get("assignedPartitions"))
                    .isInstanceOf(Integer.class)
                    .isEqualTo(PARTITION_COUNT);
            assertThat(consumer.get("totalLag"))
                    .isInstanceOf(Long.class)
                    .isEqualTo(0L);
        }

        @Test
        @DisplayName("consumer.status=reachable с totalLag > 0 когда сообщения отправлены, но не все обработаны")
        void shouldReportConsumerReachableWithPositiveLag() throws Exception {
            // Given — топик с сообщениями, consumer зафиксировал только часть
            String lagTopic = "health-consumer-lag-test-" + UUID.randomUUID().toString().substring(0, 8);
            String lagGroupId = "health-lag-group-" + UUID.randomUUID().toString().substring(0, 8);

            createTopicIfAbsent(adminClient, lagTopic, PARTITION_COUNT, REPLICATION_FACTOR);
            produceMessages(lagTopic, 5);

            // Консьюмим только 2 из 5 и фиксируем
            consumeAndCommit(lagTopic, lagGroupId, 2);

            String originalGroupId = withTemporaryGroupId(lagGroupId);
            try {
                // When
                Health health = healthIndicator.health();

                // Then
                Map<String, Object> consumer = consumerDetails(health);
                assertThat(consumer).containsEntry("status", "reachable");
                assertThat(consumer.get("totalLag"))
                        .isInstanceOf(Long.class)
                        .asInstanceOf(InstanceOfAssertFactories.LONG)
                        .isGreaterThan(0L);
            } finally {
                restoreGroupId(originalGroupId);
            }
        }

        @Test
        @DisplayName("consumer details содержит groupId, assignedPartitions, totalLag")
        void shouldIncludeAllConsumerDetailsFields() throws Exception {
            // Given
            createTopicIfAbsent(adminClient, CONSUMER_TOPIC, PARTITION_COUNT, REPLICATION_FACTOR);
            produceMessages(CONSUMER_TOPIC, 1);
            consumeAndCommit(CONSUMER_TOPIC, CONSUMER_GROUP, 1);

            // When
            Health health = healthIndicator.health();

            // Then
            Map<String, Object> consumer = consumerDetails(health);
            assertThat(consumer)
                    .containsKeys("status", "groupId", "assignedPartitions", "totalLag")
                    .doesNotContainKeys("error", "errorType");
        }

        @Autowired
        private KafkaHealthProperties kafkaHealthProperties;

        private String withTemporaryGroupId(String groupId) {
            String original = kafkaHealthProperties.getConsumer().getGroupId();
            kafkaHealthProperties.getConsumer().setGroupId(groupId);
            return original;
        }

        private void restoreGroupId(String originalGroupId) {
            kafkaHealthProperties.getConsumer().setGroupId(originalGroupId);
        }

        @SuppressWarnings("null")
        private void produceMessages(String topic, int count) {
            for (int i = 0; i < count; i++) {
                kafkaTemplate.send(topic, "key-" + i, "value-" + i);
            }
            kafkaTemplate.flush();
        }

        private void consumeAndCommit(String topic, String groupId, int expectedCount) {
            Properties props = getProperties(groupId, expectedCount);
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
                consumer.subscribe(List.of(topic));

                int received = 0;
                long deadline = System.currentTimeMillis() + 30_000;
                while (received < expectedCount && System.currentTimeMillis() < deadline) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    for (@SuppressWarnings("unused") ConsumerRecord<String, String> ignored : records) {
                        received++;
                        if (received >= expectedCount) {
                            break;
                        }
                    }
                }
                consumer.commitSync();
            }
        }
    }

    private static @NotNull Properties getProperties(String groupId, int expectedCount) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, expectedCount);
        return props;
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            classes = KafkaHealthIntegrationTest.BaseTestConfig.class
    )
    @EnableAutoConfiguration
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("Topics: partition health monitoring")
    class TopicsCheck {

        private static final String HEALTHY_TOPIC = "health-topics-healthy";
        private static final String NONEXISTENT_TOPIC = "health-topics-nonexistent-" + UUID.randomUUID().toString().substring(0, 8);

        @DynamicPropertySource
        static void configureProperties(DynamicPropertyRegistry registry) {
            baseKafkaProperties(registry);
            registry.add("platform.health.kafka.producer.enabled", () -> "false");
            registry.add("platform.health.kafka.consumer.enabled", () -> "false");
            registry.add("platform.health.kafka.topics.enabled", () -> "true");
            registry.add("platform.health.kafka.topics.monitored-topics[0]", () -> HEALTHY_TOPIC);
        }

        @Autowired
        private KafkaHealthIndicator healthIndicator;

        @Autowired
        private AdminClient adminClient;

        @Autowired
        private KafkaHealthProperties kafkaHealthProperties;

        @BeforeAll
        void createTopics() throws Exception {
            createTopicIfAbsent(adminClient, HEALTHY_TOPIC, 1, 1);
        }

        @Test
        @DisplayName("Ключ 'topics' присутствует в details когда topics.enabled=true")
        void shouldIncludeTopicsKeyWhenEnabled() {
            // When
            Health health = healthIndicator.health();

            // Then
            assertThat(health.getDetails()).containsKey("topics");
        }

        @Test
        @DisplayName("Status=UP и topic.status=healthy для существующего топика без offline partitions")
        void shouldReportHealthyTopicAsUp() {
            // When
            Health health = healthIndicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            Map<String, Object> topicDetails = topicDetails(health, HEALTHY_TOPIC);
            assertThat(topicDetails).containsEntry("status", "healthy");
            assertThat(topicDetails.get("totalPartitions"))
                    .isInstanceOf(Integer.class)
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("topic details содержат totalPartitions без offlinePartitions для healthy топика")
        void shouldNotIncludeOfflinePartitionsForHealthyTopic() {
            // When
            Health health = healthIndicator.health();

            // Then
            Map<String, Object> topicDetails = topicDetails(health, HEALTHY_TOPIC);
            assertThat(topicDetails)
                    .containsKeys("status", "totalPartitions")
                    .doesNotContainKey("offlinePartitions")
                    .doesNotContainKey("underReplicatedPartitions");
        }

        @Test
        @DisplayName("Status=OUT_OF_SERVICE и topicsError присутствует когда monitored topic не существует")
        void shouldReportOutOfServiceWhenMonitoredTopicNotFound() {
            // Given — добавляем несуществующий топик к monitored списку
            List<String> originalTopics = kafkaHealthProperties.getTopics().getMonitoredTopics();
            kafkaHealthProperties.getTopics().setMonitoredTopics(List.of(HEALTHY_TOPIC, NONEXISTENT_TOPIC));

            try {
                // When
                Health health = healthIndicator.health();

                // Then
                assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
                assertThat(health.getDetails())
                        .containsKey("topicsError")
                        .containsKey("topicsErrorType")
                        .doesNotContainKey("topics");
            } finally {
                kafkaHealthProperties.getTopics().setMonitoredTopics(originalTopics);
            }
        }

        @Test
        @DisplayName("topics details содержат запись для каждого monitored топика")
        void shouldIncludeEntryForEachMonitoredTopic() {
            // When
            Health health = healthIndicator.health();

            // Then
            assertThat(health.getDetails()).containsKey("topics");
            @SuppressWarnings("unchecked")
            Map<String, Object> allTopics = (Map<String, Object>) health.getDetails().get("topics");
            assertThat(allTopics).containsKey(HEALTHY_TOPIC);
        }

        @Test
        @DisplayName("Ключ 'topics' отсутствует когда topics.enabled=false")
        void shouldNotIncludeTopicsWhenDisabled(@Autowired KafkaHealthProperties properties) {
            // Given
            boolean original = properties.getTopics().isEnabled();
            properties.getTopics().setEnabled(false);
            try {
                // When
                Health health = healthIndicator.health();

                // Then
                assertThat(health.getDetails()).doesNotContainKey("topics");
            } finally {
                properties.getTopics().setEnabled(original);
            }
        }

        @Test
        @DisplayName("Ключ 'topics' отсутствует когда monitoredTopics пустой список")
        void shouldNotIncludeTopicsWhenMonitoredTopicsEmpty(@Autowired KafkaHealthProperties properties) {
            // Given
            List<String> original = properties.getTopics().getMonitoredTopics();
            properties.getTopics().setMonitoredTopics(List.of());
            try {
                // When
                Health health = healthIndicator.health();

                // Then
                assertThat(health.getDetails()).doesNotContainKey("topics");
            } finally {
                properties.getTopics().setMonitoredTopics(original);
            }
        }
    }

    static void baseKafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.producer.key-serializer", StringSerializer.class::getName);
        registry.add("spring.kafka.producer.value-serializer", StringSerializer.class::getName);
        registry.add("spring.kafka.consumer.key-deserializer", StringDeserializer.class::getName);
        registry.add("spring.kafka.consumer.value-deserializer", StringDeserializer.class::getName);
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");

        registry.add("platform.health.kafka.enabled", () -> "true");
        registry.add("platform.health.kafka.async.enabled", () -> "false");
        registry.add("platform.health.kafka.timeout.request-timeout", () -> "10s");
        registry.add("platform.health.kafka.cluster.min-broker-count", () -> "1");
    }

    static void createTopicIfAbsent(AdminClient adminClient, String topic, int partitions, int replicationFactor)
            throws ExecutionException, InterruptedException, TimeoutException {
        Set<String> existing = adminClient.listTopics().names().get(10, TimeUnit.SECONDS);
        if (!existing.contains(topic)) {
            adminClient.createTopics(List.of(new NewTopic(topic, partitions, (short) replicationFactor)))
                    .all()
                    .get(10, TimeUnit.SECONDS);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> producerDetails(Health health) {
        return (Map<String, Object>) health.getDetails().get("producer");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> consumerDetails(Health health) {
        return (Map<String, Object>) health.getDetails().get("consumer");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> topicDetails(Health health, String topicName) {
        Map<String, Object> allTopics = (Map<String, Object>) health.getDetails().get("topics");
        return (Map<String, Object>) allTopics.get(topicName);
    }

    @SpringBootApplication
    @EnableKafka
    @Import({
            KafkaTopicHealthCheck.class,
            KafkaProducerHealthCheck.class,
            KafkaConsumerHealthCheck.class
    })
    static class BaseTestConfig {

        @Bean
        public AdminClient adminClient(KafkaProperties kafkaProperties) {
            Map<String, Object> props = new HashMap<>(kafkaProperties.buildAdminProperties(null));
            return AdminClient.create(props);
        }
    }
}
