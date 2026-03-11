package space.br1440.platform.healthchecks.kafka;

import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LONG;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("KafkaProducerHealthCheck")
class KafkaProducerHealthCheckTest {

    private static final String PRODUCER_METRICS_GROUP = "producer-metrics";
    private static final String PRODUCER_NODE_METRICS_GROUP = "producer-node-metrics";
    private static final String CONNECTION_COUNT = "connection-count";
    private static final String OTHER_METRIC = "request-rate";

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @InjectMocks
    private KafkaProducerHealthCheck check;

    @Nested
    @DisplayName("status=reachable")
    class Reachable {

        @Test
        @DisplayName("status=reachable и connectionCount=N когда connection-count > 0")
        void shouldReportReachableWhenConnectionCountPositive() {
            // Given
            mockMetric(PRODUCER_METRICS_GROUP, CONNECTION_COUNT, 3.0);

            // When
            Map<String, Object> details = check(check);

            // Then
            assertThat(details)
                    .containsEntry("status", "reachable")
                    .hasEntrySatisfying("connectionCount", v ->
                            assertThat(v).asInstanceOf(LONG).isEqualTo(3L));
        }

        @Test
        @DisplayName("connectionCount=1 когда connection-count=1.0")
        void shouldReportConnectionCountOne() {
            // Given
            mockMetric(PRODUCER_METRICS_GROUP, CONNECTION_COUNT, 1.0);

            // When
            Map<String, Object> details = check(check);

            // Then
            assertThat(details).containsEntry("connectionCount", 1L);
        }

        @Test
        @DisplayName("connectionCount=Long (не Double) — тип значения конвертируется корректно")
        void shouldReturnConnectionCountAsLongNotDouble() {
            // Given
            mockMetric(PRODUCER_METRICS_GROUP, CONNECTION_COUNT, 5.0);

            // When
            Object connectionCount = check(check).get("connectionCount");

            // Then
            assertThat(connectionCount).isInstanceOf(Long.class);
        }

        @Test
        @DisplayName("connectionCount усекается до Long — дробная часть отбрасывается")
        void shouldTruncateDoubleToLong() {
            // Given
            mockMetric(PRODUCER_METRICS_GROUP, CONNECTION_COUNT, 2.9);

            // When
            Map<String, Object> details = check(check);

            // Then
            assertThat(details).containsEntry("connectionCount", 2L);
        }
    }

    @Nested
    @DisplayName("status=no_connections")
    class NoConnections {

        @Test
        @DisplayName("status=no_connections когда connection-count == 0")
        void shouldReportNoConnectionsWhenZero() {
            // Given
            mockMetric(PRODUCER_METRICS_GROUP, CONNECTION_COUNT, 0.0);

            // When
            Map<String, Object> details = check(check);

            // Then
            assertThat(details)
                    .containsEntry("status", "no_connections")
                    .containsEntry("connectionCount", 0L);
        }

        @Test
        @DisplayName("status=no_connections когда connection-count < 0 (нештатное значение от клиента)")
        void shouldReportNoConnectionsWhenNegative() {
            // Given
            mockMetric(PRODUCER_METRICS_GROUP, CONNECTION_COUNT, -1.0);

            // When
            Map<String, Object> details = check(check);

            // Then
            assertThat(details).containsEntry("status", "no_connections");
        }
    }

    @Nested
    @DisplayName("status=error — отсутствие метрики")
    class MetricMissing {

        @Test
        @DisplayName("status=error когда metrics() возвращает пустую map")
        void shouldReportErrorWhenMetricsEmpty() {
            // Given
            doReturn(Map.of()).when(kafkaTemplate).metrics();

            // When
            Map<String, Object> details = check(check);

            // Then
            assertThat(details)
                    .containsEntry("status", "error")
                    .containsEntry("errorType", "IllegalStateException")
                    .containsKey("error");
        }

        @Test
        @DisplayName("status=error когда connection-count есть только в producer-node-metrics, не в producer-metrics")
        void shouldReportErrorWhenMetricOnlyInNodeGroup() {
            // Given — Kafka публикует connection-count в двух группах;
            // фильтр должен брать только из producer-metrics (агрегат), игнорируя producer-node-metrics (per-broker).
            MetricName nodeMetricName = metricName(PRODUCER_NODE_METRICS_GROUP, CONNECTION_COUNT);
            Metric nodeMetric = mock(Metric.class);
            lenient().when(nodeMetric.metricValue()).thenReturn(2.0);

            Map<MetricName, Metric> metricsMap = Map.of(nodeMetricName, nodeMetric);
            doReturn(metricsMap).when(kafkaTemplate).metrics();

            // When
            Map<String, Object> details = check(check);

            // Then
            assertThat(details)
                    .containsEntry("status", "error")
                    .containsEntry("errorType", "IllegalStateException");
        }

        @Test
        @DisplayName("status=error когда metrics() содержит другие метрики, но не connection-count")
        void shouldReportErrorWhenOtherMetricsPresentButNotConnectionCount() {
            // Given
            Metric otherMetric = mock(Metric.class);
            lenient().when(otherMetric.metricValue()).thenReturn(42.0);

            MetricName otherMetricName = metricName(PRODUCER_METRICS_GROUP, OTHER_METRIC);
            Map<MetricName, Metric> metricsMap = Map.of(otherMetricName, otherMetric);
            doReturn(metricsMap).when(kafkaTemplate).metrics();

            // When
            Map<String, Object> details = check(check);

            // Then
            assertThat(details)
                    .containsEntry("status", "error")
                    .containsEntry("errorType", "IllegalStateException");
        }

        @Test
        @DisplayName("error-message содержит имя метрики connection-count")
        void shouldIncludeMetricNameInErrorMessage() {
            // Given
            doReturn(Map.of()).when(kafkaTemplate).metrics();

            // When
            String errorMessage = (String) check(check).get("error");

            // Then
            assertThat(errorMessage).contains("connection-count");
        }
    }

    @Nested
    @DisplayName("status=error — исключение при вызове metrics()")
    class MetricsThrows {

        @Test
        @DisplayName("status=error когда kafkaTemplate.metrics() бросает RuntimeException")
        void shouldReportErrorWhenMetricsThrowsRuntimeException() {
            // Given
            when(kafkaTemplate.metrics()).thenThrow(new RuntimeException("metrics not available"));

            // When
            Map<String, Object> details = check(check);

            // Then
            assertThat(details)
                    .containsEntry("status", "error")
                    .containsEntry("errorType", "RuntimeException")
                    .containsEntry("error", "metrics not available");
        }

        @Test
        @DisplayName("status=error когда kafkaTemplate.metrics() бросает IllegalStateException")
        void shouldReportErrorWhenMetricsThrowsIllegalState() {
            // Given
            when(kafkaTemplate.metrics()).thenThrow(new IllegalStateException("producer closed"));

            // When
            Map<String, Object> details = check(check);

            // Then
            assertThat(details)
                    .containsEntry("status", "error")
                    .containsEntry("errorType", "IllegalStateException")
                    .containsEntry("error", "producer closed");
        }
    }

    @Nested
    @DisplayName("details structure")
    class DetailsStructure {

        @Test
        @DisplayName("результат помещается под ключ 'producer' в переданный map")
        void shouldPopulateProducerKeyInGivenMap() {
            // Given
            mockMetric(PRODUCER_METRICS_GROUP, CONNECTION_COUNT, 1.0);
            Map<String, Object> outer = new LinkedHashMap<>();

            // When
            check.check(outer);

            // Then
            assertThat(outer).containsKey("producer");
            assertThat(outer.get("producer")).isInstanceOf(Map.class);
        }

        @Test
        @DisplayName("не затрагивает другие ключи в переданном map")
        void shouldNotModifyOtherKeysInGivenMap() {
            // Given
            mockMetric(PRODUCER_METRICS_GROUP, CONNECTION_COUNT, 1.0);
            Map<String, Object> outer = new HashMap<>();
            outer.put("brokerCount", 3);
            outer.put("clusterId", "abc");

            // When
            check.check(outer);

            // Then
            assertThat(outer)
                    .containsEntry("brokerCount", 3)
                    .containsEntry("clusterId", "abc");
        }

        @Test
        @DisplayName("producer details содержит status и connectionCount — не содержит лишних ключей при success")
        void shouldNotContainErrorKeysOnSuccess() {
            // Given
            mockMetric(PRODUCER_METRICS_GROUP, CONNECTION_COUNT, 2.0);

            // When
            Map<String, Object> details = check(check);

            // Then
            assertThat(details)
                    .containsKeys("status", "connectionCount")
                    .doesNotContainKeys("error", "errorType");
        }

        @Test
        @DisplayName("producer details содержит status, error и errorType — не содержит connectionCount при error")
        void shouldNotContainConnectionCountOnError() {
            // Given
            doReturn(Map.of()).when(kafkaTemplate).metrics();

            // When
            Map<String, Object> details = check(check);

            // Then
            assertThat(details)
                    .containsKeys("status", "error", "errorType")
                    .doesNotContainKey("connectionCount");
        }

        @Test
        @DisplayName("ключи status и connectionCount идут первыми (LinkedHashMap — порядок вставки)")
        void shouldReturnDetailsInInsertionOrder() {
            // Given
            mockMetric(PRODUCER_METRICS_GROUP, CONNECTION_COUNT, 1.0);

            // When
            java.util.List<String> keys = new java.util.ArrayList<>(check(check).keySet());

            // Then
            assertThat(keys).containsExactly("status", "connectionCount");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> check(KafkaProducerHealthCheck check) {
        Map<String, Object> outer = new LinkedHashMap<>();
        check.check(outer);
        return (Map<String, Object>) outer.get("producer");
    }

    private void mockMetric(String group, String name, double value) {
        MetricName metricName = metricName(group, name);
        Metric metric = mock(Metric.class);
        when(metric.metricValue()).thenReturn(value);
        Map<MetricName, Metric> metrics = Map.of(metricName, metric);
        doReturn(metrics).when(kafkaTemplate).metrics();
    }

    private static MetricName metricName(String group, String name) {
        return new MetricName(name, group, "", Map.of());
    }
}
