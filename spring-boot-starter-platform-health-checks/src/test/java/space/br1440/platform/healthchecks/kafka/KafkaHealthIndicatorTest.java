package space.br1440.platform.healthchecks.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.Node;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LONG;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("KafkaHealthIndicator")
class KafkaHealthIndicatorTest {

    private static final String CLUSTER_ID = "test-cluster-id";
    private static final Collection<Node> THREE_NODES = List.of(
            new Node(0, "broker-0", 9092),
            new Node(1, "broker-1", 9092),
            new Node(2, "broker-2", 9092)
    );

    private static final String PRODUCER_METRICS_GROUP = "producer-metrics";
    private static final String PRODUCER_NODE_METRICS_GROUP = "producer-node-metrics";
    private static final String CONNECTION_COUNT_METRIC = "connection-count";

    @Mock
    private AdminClient adminClient;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private KafkaHealthProperties properties;

    @BeforeEach
    void setUp() {
        properties = new KafkaHealthProperties();
        properties.getAsync().setEnabled(false);
    }

    @Test
    @DisplayName("UP когда кластер доступен и brokerCount >= minBrokerCount")
    void shouldReturnUpWhenClusterAccessible() {
        // Given
        mockCluster(THREE_NODES, CLUSTER_ID);

        // When
        Health health = indicator().health();

        // Then
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("brokerCount", 3)
                .containsEntry("clusterId", CLUSTER_ID)
                .doesNotContainKey("clusterWarning");
    }

    @Test
    @DisplayName("clusterId=unknown когда кластер возвращает null clusterId")
    void shouldReportUnknownClusterIdWhenNull() {
        // Given
        mockCluster(THREE_NODES, null);

        // When
        Health health = indicator().health();

        // Then
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("clusterId", "unknown");
    }

    @Test
    @DisplayName("OUT_OF_SERVICE когда brokerCount < minBrokerCount")
    void shouldReturnOutOfServiceWhenBrokerCountBelowMinimum() {
        // Given
        properties.getCluster().setMinBrokerCount(5);
        mockCluster(THREE_NODES, CLUSTER_ID);

        // When
        Health health = indicator().health();

        // Then
        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(health.getDetails())
                .containsEntry("brokerCount", 3)
                .containsKey("clusterWarning");
    }

    @Test
    @DisplayName("DOWN когда AdminClient.describeCluster бросает исключение")
    void shouldReturnDownWhenClusterUnreachable() throws Exception {
        // Given
        DescribeClusterResult result = mock(DescribeClusterResult.class);
        KafkaFuture<Collection<Node>> failedFuture = failedFuture(
                new ExecutionException("Connection refused", new RuntimeException())
        );
        when(result.nodes()).thenReturn(failedFuture);
        when(adminClient.describeCluster()).thenReturn(result);

        // When
        Health health = indicator().health();

        // Then
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsKey("error")
                .containsKey("errorType");
    }

    @Test
    @DisplayName("Ключ 'producer' отсутствует когда producer.enabled=false (по умолчанию)")
    void shouldNotIncludeProducerDetailsWhenDisabled() {
        // Given
        mockCluster(THREE_NODES, CLUSTER_ID);

        // When
        Health health = indicator().health();

        // Then
        assertThat(health.getDetails()).doesNotContainKey("producer");
    }

    @Test
    @DisplayName("Ключ 'consumer' отсутствует когда consumer.enabled=false (по умолчанию)")
    void shouldNotIncludeConsumerDetailsWhenDisabled() {
        // Given
        mockCluster(THREE_NODES, CLUSTER_ID);

        // When
        Health health = indicator().health();

        // Then
        assertThat(health.getDetails()).doesNotContainKey("consumer");
    }

    @Test
    @DisplayName("Ключ 'topics' отсутствует когда topics.enabled=false (по умолчанию)")
    void shouldNotIncludeTopicsDetailsWhenDisabled() {
        // Given
        mockCluster(THREE_NODES, CLUSTER_ID);

        // When
        Health health = indicator().health();

        // Then
        assertThat(health.getDetails()).doesNotContainKey("topics");
    }

    @Test
    @DisplayName("Ключ 'topics' отсутствует когда monitoredTopics пуст")
    void shouldNotIncludeTopicsDetailsWhenMonitoredTopicsEmpty() {
        // Given
        properties.getTopics().setEnabled(true);
        mockCluster(THREE_NODES, CLUSTER_ID);

        // When
        Health health = indicator().health();

        // Then
        assertThat(health.getDetails()).doesNotContainKey("topics");
    }

    @Test
    @DisplayName("details содержит brokerCount и clusterId в предсказуемом порядке")
    void shouldIncludeClusterDetailsInPredictableOrder() {
        // Given
        mockCluster(THREE_NODES, CLUSTER_ID);

        // When
        Map<String, Object> details = indicator().health().getDetails();

        // Then
        List<String> keys = new ArrayList<>(details.keySet());
        assertThat(keys.indexOf("brokerCount")).isLessThan(keys.indexOf("clusterId"));
    }

    @Nested
    @DisplayName("producer check")
    class ProducerCheck {

        @BeforeEach
        void enableProducer() {
            properties.getProducer().setEnabled(true);
            mockCluster(THREE_NODES, CLUSTER_ID);
        }

        @Test
        @DisplayName("status=reachable и connectionCount > 0 когда метрика connection-count > 0")
        void shouldReportReachableWhenConnectionCountPositive() {
            // Given
            mockConnectionCount(PRODUCER_METRICS_GROUP, CONNECTION_COUNT_METRIC, 3.0);

            // When
            Map<String, Object> producer = producerDetails(indicator().health());

            // Then
            assertThat(producer)
                    .containsEntry("status", "reachable")
                    .hasEntrySatisfying("connectionCount", v ->
                            assertThat(v).asInstanceOf(LONG).isEqualTo(3L));
        }

        @Test
        @DisplayName("status=no_connections когда connection-count == 0")
        void shouldReportNoConnectionsWhenConnectionCountZero() {
            // Given
            mockConnectionCount(PRODUCER_METRICS_GROUP, CONNECTION_COUNT_METRIC, 0.0);

            // When
            Map<String, Object> producer = producerDetails(indicator().health());

            // Then
            assertThat(producer)
                    .containsEntry("status", "no_connections")
                    .containsEntry("connectionCount", 0L);
        }

        @Test
        @DisplayName("status=error когда метрика connection-count отсутствует в producer-metrics")
        void shouldReportErrorWhenMetricMissing() {
            // Given
            when(kafkaTemplate.metrics()).thenReturn(Map.of());

            // When
            Map<String, Object> producer = producerDetails(indicator().health());

            // Then
            assertThat(producer)
                    .containsEntry("status", "error")
                    .containsEntry("errorType", "IllegalStateException")
                    .containsKey("error");
        }

        @Test
        @DisplayName("status=error когда connection-count есть в producer-node-metrics, но не в producer-metrics")
        void shouldReportErrorWhenMetricOnlyInNodeMetricsGroup() {
            // Given
            MetricName nodeMetricName = new MetricName(CONNECTION_COUNT_METRIC, PRODUCER_NODE_METRICS_GROUP, "", Map.of());
            Metric nodeMetric = mock(Metric.class);
            lenient().when(nodeMetric.metricValue()).thenReturn(2.0);

            Map<MetricName, Metric> metricsWithNodeGroupOnly = Map.of(nodeMetricName, nodeMetric);
            doReturn(metricsWithNodeGroupOnly).when(kafkaTemplate).metrics();

            // When
            Map<String, Object> producer = producerDetails(indicator().health());

            // Then
            assertThat(producer)
                    .containsEntry("status", "error")
                    .containsEntry("errorType", "IllegalStateException");
        }

        @Test
        @DisplayName("status=error когда kafkaTemplate.metrics() бросает исключение")
        void shouldReportErrorWhenMetricsThrows() {
            // Given
            when(kafkaTemplate.metrics()).thenThrow(new RuntimeException("metrics not available"));

            // When
            Map<String, Object> producer = producerDetails(indicator().health());

            // Then
            assertThat(producer)
                    .containsEntry("status", "error")
                    .containsEntry("errorType", "RuntimeException")
                    .containsEntry("error", "metrics not available");
        }

        @Test
        @DisplayName("cluster остаётся UP когда producer.status=reachable")
        void shouldKeepClusterUpWhenProducerReachable() {
            // Given
            mockConnectionCount(PRODUCER_METRICS_GROUP, CONNECTION_COUNT_METRIC, 1.0);

            // When
            Health health = indicator().health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsKey("producer");
        }

        @Test
        @DisplayName("cluster остаётся UP когда producer.status=error (producer не влияет на общий статус)")
        void shouldKeepClusterUpEvenWhenProducerError() {
            // Given
            when(kafkaTemplate.metrics()).thenReturn(Map.of());

            // When
            Health health = indicator().health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(producerDetails(health)).containsEntry("status", "error");
        }

        @Test
        @DisplayName("connectionCount является Long (не Double)")
        void shouldReturnConnectionCountAsLong() {
            // Given
            mockConnectionCount(PRODUCER_METRICS_GROUP, CONNECTION_COUNT_METRIC, 2.7);

            // When
            Object connectionCount = producerDetails(indicator().health()).get("connectionCount");

            // Then
            assertThat(connectionCount).isInstanceOf(Long.class);
            assertThat((Long) connectionCount).isEqualTo(2L);
        }
    }

    private KafkaHealthIndicator indicator() {
        KafkaProducerHealthCheck producerCheck = new KafkaProducerHealthCheck(kafkaTemplate);
        KafkaConsumerHealthCheck consumerCheck = new KafkaConsumerHealthCheck(properties);
        KafkaTopicHealthCheck topicCheck = new KafkaTopicHealthCheck(properties);
        return new KafkaHealthIndicator(adminClient, properties, producerCheck, consumerCheck, topicCheck);
    }

    private void mockCluster(Collection<Node> nodes, String clusterId) {
        DescribeClusterResult result = mock(DescribeClusterResult.class);
        when(result.nodes()).thenReturn(KafkaFuture.completedFuture(nodes));
        when(result.clusterId()).thenReturn(KafkaFuture.completedFuture(clusterId));
        when(adminClient.describeCluster()).thenReturn(result);
    }

    private void mockConnectionCount(String group, String name, double value) {
        MetricName metricName = new MetricName(name, group, "", Map.of());
        Metric metric = mock(Metric.class);
        when(metric.metricValue()).thenReturn(value);

        Map<MetricName, Metric> metrics = Map.of(metricName, metric);
        doReturn(metrics).when(kafkaTemplate).metrics();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> producerDetails(Health health) {
        return (Map<String, Object>) health.getDetails().get("producer");
    }

    @SuppressWarnings("unchecked")
    private static <T> KafkaFuture<T> failedFuture(Exception cause) throws Exception {
        KafkaFuture<T> future = mock(KafkaFuture.class);
        when(future.get(anyLong(), any())).thenThrow(cause);
        return future;
    }
}
