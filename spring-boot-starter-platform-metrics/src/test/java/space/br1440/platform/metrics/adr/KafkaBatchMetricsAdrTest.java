package space.br1440.platform.metrics.adr;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.metrics.MetricTags;
import space.br1440.platform.metrics.PlatformMetrics;
import space.br1440.platform.metrics.adr.support.MetricsTestSupport;
import space.br1440.platform.metrics.adr.support.PlatformMetricsFactory;
import space.br1440.platform.metrics.kafka.consumer.BatchProcessInterceptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ADR: Kafka batch consumer metrics")
class KafkaBatchMetricsAdrTest {

    private static final String TOPIC = "orders-batch";
    private static final String CLIENT_ID = "batch-client-0";
    private static final String GROUP = "batch-group";

    private MeterRegistry registry;
    private PlatformMetrics platformMetrics;
    private BatchProcessInterceptor interceptor;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        platformMetrics = PlatformMetricsFactory.create(registry);
        interceptor = new BatchProcessInterceptor(platformMetrics);
    }

    @Test
    @DisplayName("success publishes batch processing timer and batch size summary")
    @SuppressWarnings("unchecked")
    void successPublishesBatchMetrics() {
        Consumer<Object, Object> consumer = mockConsumer();
        ConsumerRecords<Object, Object> records = records(TOPIC, 3);
        interceptor.intercept(records, consumer);
        interceptor.success(records, consumer);

        Timer timer = MetricsTestSupport.findTimer(registry, "platform_consumer_batch_processing_seconds",
                MetricTags.TOPIC, TOPIC, MetricTags.CLIENT_ID, CLIENT_ID, MetricTags.CONSUMER_GROUP, GROUP,
                MetricTags.OUTCOME, MetricTags.OUTCOME_SUCCESS);
        assertThat(timer.count()).isEqualTo(1L);

        DistributionSummary summary = registry.find("platform_consumer_batch_messages_total").summaries().stream()
                .filter(s -> TOPIC.equals(s.getId().getTag(MetricTags.TOPIC))).findFirst().orElseThrow();
        assertThat(summary.count()).isEqualTo(1L);
        assertThat(summary.totalAmount()).isEqualTo(3.0);
    }

    // ─── K6B: batch success increments consumer_messages_total ───────────

    @Test
    @DisplayName("K6B: batch success increments platform_consumer_messages_total by record count")
    @SuppressWarnings("unchecked")
    void successIncrementsBatchConsumerMessagesTotalByRecordCount() {
        Consumer<Object, Object> consumer = mockConsumer();
        ConsumerRecords<Object, Object> records = records(TOPIC, 3);
        interceptor.intercept(records, consumer);
        interceptor.success(records, consumer);

        Counter counter = MetricsTestSupport.findCounter(registry, "platform_consumer_messages_total",
                MetricTags.TOPIC, TOPIC,
                MetricTags.CLIENT_ID, CLIENT_ID,
                MetricTags.CONSUMER_GROUP, GROUP,
                MetricTags.RESULT, MetricTags.RESULT_SUCCESS);
        assertThat(counter.count()).isEqualTo(3.0);

        // existing batch metrics unaffected
        Timer timer = MetricsTestSupport.findTimer(registry, "platform_consumer_batch_processing_seconds",
                MetricTags.TOPIC, TOPIC, MetricTags.CLIENT_ID, CLIENT_ID,
                MetricTags.CONSUMER_GROUP, GROUP, MetricTags.OUTCOME, MetricTags.OUTCOME_SUCCESS);
        assertThat(timer.count()).isEqualTo(1L);

        DistributionSummary summary = registry.find("platform_consumer_batch_messages_total").summaries()
                .stream().filter(s -> TOPIC.equals(s.getId().getTag(MetricTags.TOPIC))).findFirst().orElseThrow();
        assertThat(summary.totalAmount()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("K6B: multi-topic batch success increments consumer_messages_total per topic by topic record count")
    @SuppressWarnings("unchecked")
    void successMultiTopicBatchIncrementsByTopicRecordCount() {
        String topicA = "topic-a";
        String topicB = "topic-b";
        Consumer<Object, Object> consumer = mockConsumer();
        ConsumerRecords<Object, Object> records = recordsMultiTopic(topicA, 2, topicB, 1);
        interceptor.intercept(records, consumer);
        interceptor.success(records, consumer);

        Counter counterA = MetricsTestSupport.findCounter(registry, "platform_consumer_messages_total",
                MetricTags.TOPIC, topicA,
                MetricTags.CLIENT_ID, CLIENT_ID,
                MetricTags.CONSUMER_GROUP, GROUP,
                MetricTags.RESULT, MetricTags.RESULT_SUCCESS);
        assertThat(counterA.count()).isEqualTo(2.0);

        Counter counterB = MetricsTestSupport.findCounter(registry, "platform_consumer_messages_total",
                MetricTags.TOPIC, topicB,
                MetricTags.CLIENT_ID, CLIENT_ID,
                MetricTags.CONSUMER_GROUP, GROUP,
                MetricTags.RESULT, MetricTags.RESULT_SUCCESS);
        assertThat(counterB.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("K6B: batch failure still increments consumer_messages_total with result=failure (unchanged behavior)")
    @SuppressWarnings("unchecked")
    void failureStillIncrementsConsumerMessagesTotalWithResultFailure() {
        Consumer<Object, Object> consumer = mockConsumer();
        ConsumerRecords<Object, Object> records = records(TOPIC, 3);
        interceptor.intercept(records, consumer);
        interceptor.failure(records, new RuntimeException("batch error"), consumer);

        Counter failureCounter = MetricsTestSupport.findCounter(registry, "platform_consumer_messages_total",
                MetricTags.TOPIC, TOPIC,
                MetricTags.CLIENT_ID, CLIENT_ID,
                MetricTags.CONSUMER_GROUP, GROUP,
                MetricTags.RESULT, MetricTags.RESULT_FAILURE);
        assertThat(failureCounter.count()).isEqualTo(1.0);

        // success counter must remain absent on failure
        assertThat(MetricsTestSupport.findCounterOrNull(registry, "platform_consumer_messages_total",
                MetricTags.TOPIC, TOPIC,
                MetricTags.CLIENT_ID, CLIENT_ID,
                MetricTags.CONSUMER_GROUP, GROUP,
                MetricTags.RESULT, MetricTags.RESULT_SUCCESS)).isNull();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Consumer<Object, Object> mockConsumer() {
        Consumer<Object, Object> consumer = mock(Consumer.class);
        MetricName metricName = new MetricName("records-consumed-total", "consumer-metrics", "",
                Map.of("client-id", CLIENT_ID));
        Metric metric = mock(Metric.class);
        Map<MetricName, Metric> metricsMap = new HashMap<>();
        metricsMap.put(metricName, metric);
        doReturn(metricsMap).when(consumer).metrics();
        ConsumerGroupMetadata groupMetadata = mock(ConsumerGroupMetadata.class);
        when(groupMetadata.groupId()).thenReturn(GROUP);
        when(consumer.groupMetadata()).thenReturn(groupMetadata);
        return consumer;
    }

    private static ConsumerRecords<Object, Object> records(String topic, int count) {
        Map<TopicPartition, List<ConsumerRecord<Object, Object>>> map = new HashMap<>();
        TopicPartition tp = new TopicPartition(topic, 0);
        List<ConsumerRecord<Object, Object>> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new ConsumerRecord<>(topic, 0, i, "k" + i, "v" + i));
        }
        map.put(tp, list);
        return new ConsumerRecords<>(map);
    }

    private static ConsumerRecords<Object, Object> recordsMultiTopic(String topicA, int countA,
                                                                      String topicB, int countB) {
        Map<TopicPartition, List<ConsumerRecord<Object, Object>>> map = new HashMap<>();

        List<ConsumerRecord<Object, Object>> listA = new ArrayList<>();
        for (int i = 0; i < countA; i++) {
            listA.add(new ConsumerRecord<>(topicA, 0, i, "ka" + i, "va" + i));
        }
        map.put(new TopicPartition(topicA, 0), listA);

        List<ConsumerRecord<Object, Object>> listB = new ArrayList<>();
        for (int i = 0; i < countB; i++) {
            listB.add(new ConsumerRecord<>(topicB, 0, i, "kb" + i, "vb" + i));
        }
        map.put(new TopicPartition(topicB, 0), listB);

        return new ConsumerRecords<>(map);
    }
}
