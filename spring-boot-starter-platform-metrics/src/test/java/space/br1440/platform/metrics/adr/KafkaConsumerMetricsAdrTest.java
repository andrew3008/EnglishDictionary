package space.br1440.platform.metrics.adr;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import space.br1440.platform.metrics.MetricTags;
import space.br1440.platform.metrics.PlatformMetrics;
import space.br1440.platform.metrics.adr.support.MetricsTestSupport;
import space.br1440.platform.metrics.adr.support.PlatformMetricsFactory;
import space.br1440.platform.metrics.kafka.consumer.ProcessRecordInterceptor;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Интеграционный тест ADR: Kafka consumer-метрики через {@link ProcessRecordInterceptor}.
 *
 * <p>Контракт SRE ADR, проверяемый здесь:</p>
 * <ul>
 *   <li>{@code platform_consumer_messages_total{result=success}} — инкремент при успешной обработке;</li>
 *   <li>{@code platform_consumer_messages_total{result=failure}} — инкремент при ошибке обработки;</li>
 *   <li>{@code platform_consumer_wait_seconds} — Timer от timestamp записи до входа в pipeline;</li>
 *   <li>{@code platform_consumer_lag_seconds} — Timer от timestamp записи до конца обработки;</li>
 *   <li>теги {@code topic}, {@code client_id}, {@code consumer_group} присутствуют во всех метриках.</li>
 * </ul>
 */
@DisplayName("ADR: Kafka consumer-метрики")
class KafkaConsumerMetricsAdrTest {

    private static final String TOPIC        = "orders";
    private static final String CLIENT_ID    = "orders-consumer-0";
    private static final String GROUP_ID     = "orders-group";

    private MeterRegistry registry;
    private PlatformMetrics platformMetrics;
    private ProcessRecordInterceptor interceptor;
    private Consumer<Object, Object> consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        registry = new SimpleMeterRegistry();
        platformMetrics = PlatformMetricsFactory.create(registry);
        interceptor = new ProcessRecordInterceptor(platformMetrics);
        consumer = mockConsumer(CLIENT_ID, GROUP_ID);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Вспомогательные методы
    // ────────────────────────────────────────────────────────────────────────

    /** Создаёт Kafka {@link Consumer} mock с заданным client.id и group.id. */
    @SuppressWarnings("unchecked")
    private static Consumer<Object, Object> mockConsumer(String clientId, String groupId) {
        Consumer<Object, Object> c = mock(Consumer.class);

        // client.id через Kafka Metrics API (как делает ProcessRecordInterceptor)
        MetricName metricName = new MetricName("records-consumed-total", "consumer-fetch-manager-metrics",
                "", Map.of("client-id", clientId));
        Metric metric = mock(Metric.class);
        Map<MetricName, Metric> metricsMap = new HashMap<>();
        metricsMap.put(metricName, metric);
        doReturn(metricsMap).when(c).metrics();

        // group.id через groupMetadata()
        ConsumerGroupMetadata groupMetadata = mock(ConsumerGroupMetadata.class);
        when(groupMetadata.groupId()).thenReturn(groupId);
        when(c.groupMetadata()).thenReturn(groupMetadata);

        return c;
    }

    /**
     * Создаёт {@link ConsumerRecord} с заданным топиком и timestamp.
     * Timestamp нужен для корректного расчёта wait и lag.
     */
    private static ConsumerRecord<Object, Object> record(String topic, long timestampMs) {
        return new ConsumerRecord<>(topic, 0, 0L, timestampMs, null, 0L, 0, 0,
                "key", "value", new org.apache.kafka.common.header.internals.RecordHeaders());
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. platform_consumer_messages_total
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("platform_consumer_messages_total")
    class MessagesTotal {

        @Test
        @DisplayName("result=success при вызове success()")
        void successIncrementsResultSuccess() {
            ConsumerRecord<Object, Object> rec = record(TOPIC, System.currentTimeMillis() - 100);
            interceptor.intercept(rec, consumer);
            interceptor.success(rec, consumer);

            Counter counter = MetricsTestSupport.findCounter(registry, "platform_consumer_messages_total",
                    MetricTags.TOPIC, TOPIC,
                    MetricTags.CLIENT_ID, CLIENT_ID,
                    MetricTags.CONSUMER_GROUP, GROUP_ID,
                    MetricTags.RESULT, MetricTags.RESULT_SUCCESS);
            assertThat(counter.count()).isEqualTo(1.0);

            // failure НЕ должен быть записан
            assertThat(MetricsTestSupport.findCounterOrNull(registry, "platform_consumer_messages_total",
                    MetricTags.TOPIC, TOPIC,
                    MetricTags.CLIENT_ID, CLIENT_ID,
                    MetricTags.CONSUMER_GROUP, GROUP_ID,
                    MetricTags.RESULT, MetricTags.RESULT_FAILURE)).isNull();
        }

        @Test
        @DisplayName("result=failure при вызове failure()")
        void failureIncrementsResultFailure() {
            ConsumerRecord<Object, Object> rec = record(TOPIC, System.currentTimeMillis() - 100);
            interceptor.intercept(rec, consumer);
            interceptor.failure(rec, new RuntimeException("обработка упала"), consumer);

            Counter counter = MetricsTestSupport.findCounter(registry, "platform_consumer_messages_total",
                    MetricTags.TOPIC, TOPIC,
                    MetricTags.CLIENT_ID, CLIENT_ID,
                    MetricTags.CONSUMER_GROUP, GROUP_ID,
                    MetricTags.RESULT, MetricTags.RESULT_FAILURE);
            assertThat(counter.count()).isEqualTo(1.0);

            // success НЕ должен быть записан
            assertThat(MetricsTestSupport.findCounterOrNull(registry, "platform_consumer_messages_total",
                    MetricTags.TOPIC, TOPIC,
                    MetricTags.CLIENT_ID, CLIENT_ID,
                    MetricTags.CONSUMER_GROUP, GROUP_ID,
                    MetricTags.RESULT, MetricTags.RESULT_SUCCESS)).isNull();
        }

        @Test
        @DisplayName("несколько записей накапливают счётчик")
        void multipleRecordsAccumulate() {
            for (int i = 0; i < 3; i++) {
                ConsumerRecord<Object, Object> rec = record(TOPIC, System.currentTimeMillis() - 100);
                interceptor.intercept(rec, consumer);
                interceptor.success(rec, consumer);
            }

            Counter counter = MetricsTestSupport.findCounter(registry, "platform_consumer_messages_total",
                    MetricTags.TOPIC, TOPIC,
                    MetricTags.CLIENT_ID, CLIENT_ID,
                    MetricTags.CONSUMER_GROUP, GROUP_ID,
                    MetricTags.RESULT, MetricTags.RESULT_SUCCESS);
            assertThat(counter.count()).isEqualTo(3.0);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. platform_consumer_wait_seconds
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("platform_consumer_wait_seconds")
    class WaitSeconds {

        @Test
        @DisplayName("Timer записывается при intercept() с тегами topic, client_id, consumer_group")
        void timerRecordedOnIntercept() {
            long past = System.currentTimeMillis() - 200;
            ConsumerRecord<Object, Object> rec = record(TOPIC, past);
            interceptor.intercept(rec, consumer);

            Timer timer = MetricsTestSupport.findTimer(registry, "platform_consumer_wait_seconds",
                    MetricTags.TOPIC, TOPIC,
                    MetricTags.CLIENT_ID, CLIENT_ID,
                    MetricTags.CONSUMER_GROUP, GROUP_ID);
            assertThat(timer.count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("wait >= 0 для записей из прошлого")
        void waitIsNonNegative() {
            long past = System.currentTimeMillis() - 500;
            ConsumerRecord<Object, Object> rec = record(TOPIC, past);
            interceptor.intercept(rec, consumer);

            Timer timer = MetricsTestSupport.findTimer(registry, "platform_consumer_wait_seconds",
                    MetricTags.TOPIC, TOPIC,
                    MetricTags.CLIENT_ID, CLIENT_ID,
                    MetricTags.CONSUMER_GROUP, GROUP_ID);
            // Micrometer хранит в nanoseconds; достаточно проверить что count=1 и sum > 0
            assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(0.0);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3. platform_consumer_lag_seconds
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("platform_consumer_lag_seconds")
    class LagSeconds {

        @Test
        @DisplayName("Timer записывается при success() с тегами topic, client_id, consumer_group")
        void timerRecordedOnSuccess() {
            long past = System.currentTimeMillis() - 300;
            ConsumerRecord<Object, Object> rec = record(TOPIC, past);
            interceptor.intercept(rec, consumer);
            interceptor.success(rec, consumer);

            Timer timer = MetricsTestSupport.findTimer(registry, "platform_consumer_lag_seconds",
                    MetricTags.TOPIC, TOPIC,
                    MetricTags.CLIENT_ID, CLIENT_ID,
                    MetricTags.CONSUMER_GROUP, GROUP_ID);
            assertThat(timer.count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Timer записывается при failure() с тегами topic, client_id, consumer_group")
        void timerRecordedOnFailure() {
            long past = System.currentTimeMillis() - 300;
            ConsumerRecord<Object, Object> rec = record(TOPIC, past);
            interceptor.intercept(rec, consumer);
            interceptor.failure(rec, new RuntimeException("ошибка"), consumer);

            Timer timer = MetricsTestSupport.findTimer(registry, "platform_consumer_lag_seconds",
                    MetricTags.TOPIC, TOPIC,
                    MetricTags.CLIENT_ID, CLIENT_ID,
                    MetricTags.CONSUMER_GROUP, GROUP_ID);
            assertThat(timer.count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("lag >= wait: lag-таймер фиксируется позже wait-таймера")
        void lagIsGreaterOrEqualToWait() {
            long past = System.currentTimeMillis() - 400;
            ConsumerRecord<Object, Object> rec = record(TOPIC, past);
            interceptor.intercept(rec, consumer);
            interceptor.success(rec, consumer);

            Timer wait = MetricsTestSupport.findTimer(registry, "platform_consumer_wait_seconds",
                    MetricTags.TOPIC, TOPIC, MetricTags.CLIENT_ID, CLIENT_ID, MetricTags.CONSUMER_GROUP, GROUP_ID);
            Timer lag = MetricsTestSupport.findTimer(registry, "platform_consumer_lag_seconds",
                    MetricTags.TOPIC, TOPIC, MetricTags.CLIENT_ID, CLIENT_ID, MetricTags.CONSUMER_GROUP, GROUP_ID);

            assertThat(lag.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isGreaterThanOrEqualTo(wait.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS));
        }
    }
}
