package space.br1440.platform.metrics.adr;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import space.br1440.platform.metrics.MetricTags;
import space.br1440.platform.metrics.PlatformMetrics;
import space.br1440.platform.metrics.adr.support.MetricsTestSupport;
import space.br1440.platform.metrics.adr.support.PlatformMetricsFactory;
import space.br1440.platform.metrics.kafka.producer.MetricProducerPostProcessor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Интеграционный тест ADR: Kafka producer-метрики через {@link MetricProducerPostProcessor}.
 *
 * <p>Контракт SRE ADR, проверяемый здесь:</p>
 * <ul>
 *   <li>{@code platform_producer_messages_total{result=success}} — инкремент при успешной отправке;</li>
 *   <li>{@code platform_producer_messages_total{result=failure}} — инкремент при ошибке отправки;</li>
 *   <li>{@code platform_producer_lag_seconds} — Timer latency отправки (только при успехе);</li>
 *   <li>теги {@code topic}, {@code client_id} присутствуют во всех метриках;</li>
 *   <li>покрыты оба пути: с Callback и без (TimingFuture).</li>
 * </ul>
 */
@DisplayName("ADR: Kafka producer-метрики")
class KafkaProducerMetricsAdrTest {

    private static final String TOPIC     = "payments";
    private static final String CLIENT_ID = "payments-producer-0";

    private MeterRegistry registry;
    private PlatformMetrics platformMetrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        platformMetrics = PlatformMetricsFactory.create(registry);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Вспомогательные методы
    // ────────────────────────────────────────────────────────────────────────

    /** Создаёт mock Producer с настроенным client.id. */
    @SuppressWarnings("unchecked")
    private static Producer<Object, Object> mockRawProducer(String clientId) {
        Producer<Object, Object> p = mock(Producer.class);
        MetricName metricName = new MetricName("record-send-total", "producer-metrics",
                "", Map.of("client-id", clientId));
        Metric metric = mock(Metric.class);
        Map<MetricName, Metric> metricsMap = new HashMap<>();
        metricsMap.put(metricName, metric);
        doReturn(metricsMap).when(p).metrics();
        return p;
    }

    /** Оборачивает raw producer через PostProcessor и возвращает decorated. */
    private Producer<Object, Object> decorate(Producer<Object, Object> rawProducer) {
        return new MetricProducerPostProcessor(platformMetrics).apply(rawProducer);
    }

    private static ProducerRecord<Object, Object> record() {
        return new ProducerRecord<>(TOPIC, "key", "value");
    }

    @SuppressWarnings("deprecation")
    private static RecordMetadata successMetadata() {
        return new RecordMetadata(new TopicPartition(TOPIC, 0), 0L, 0L, 0L, -1L, 0, 0);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. Путь с Callback
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("С Callback")
    class WithCallback {

        @Test
        @DisplayName("result=success + lag_seconds при успешном completion Callback")
        @SuppressWarnings("unchecked")
        void callbackSuccessIncrementsResultSuccessAndRecordsLag() {
            RecordMetadata meta = successMetadata();
            Producer<Object, Object> raw = mockRawProducer(CLIENT_ID);

            // Delegate немедленно вызывает callback без ошибки
            doAnswer(inv -> {
                Callback cb = inv.getArgument(1);
                cb.onCompletion(meta, null);
                return CompletableFuture.completedFuture(meta);
            }).when(raw).send(any(ProducerRecord.class), any(Callback.class));

            decorate(raw).send(record(), (metadata, exception) -> { /* пользовательский callback */ });

            Counter counter = MetricsTestSupport.findCounter(registry, "platform_producer_messages_total",
                    MetricTags.TOPIC, TOPIC,
                    MetricTags.CLIENT_ID, CLIENT_ID,
                    MetricTags.RESULT, MetricTags.RESULT_SUCCESS);
            assertThat(counter.count()).isEqualTo(1.0);

            // lag_seconds записан только при успехе
            Timer lagTimer = MetricsTestSupport.findTimer(registry, "platform_producer_lag_seconds",
                    MetricTags.TOPIC, TOPIC,
                    MetricTags.CLIENT_ID, CLIENT_ID);
            assertThat(lagTimer.count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("result=failure при ошибке в Callback; lag_seconds не записывается")
        @SuppressWarnings("unchecked")
        void callbackFailureIncrementsResultFailure() {
            RuntimeException brokerError = new RuntimeException("broker unavailable");
            Producer<Object, Object> raw = mockRawProducer(CLIENT_ID);

            doAnswer(inv -> {
                Callback cb = inv.getArgument(1);
                cb.onCompletion(null, brokerError);
                return CompletableFuture.failedFuture(new ExecutionException(brokerError));
            }).when(raw).send(any(ProducerRecord.class), any(Callback.class));

            decorate(raw).send(record(), (metadata, exception) -> { /* пользовательский callback */ });

            Counter counter = MetricsTestSupport.findCounter(registry, "platform_producer_messages_total",
                    MetricTags.TOPIC, TOPIC,
                    MetricTags.CLIENT_ID, CLIENT_ID,
                    MetricTags.RESULT, MetricTags.RESULT_FAILURE);
            assertThat(counter.count()).isEqualTo(1.0);

            // lag_seconds НЕ записывается при ошибке
            assertThat(MetricsTestSupport.findCounterOrNull(registry, "platform_producer_messages_total",
                    MetricTags.TOPIC, TOPIC,
                    MetricTags.CLIENT_ID, CLIENT_ID,
                    MetricTags.RESULT, MetricTags.RESULT_SUCCESS)).isNull();
        }

        @Test
        @DisplayName("пользовательский Callback=null не бросает NPE")
        @SuppressWarnings("unchecked")
        void nullCallbackIsHandledGracefully() {
            RecordMetadata meta = successMetadata();
            Producer<Object, Object> raw = mockRawProducer(CLIENT_ID);

            doAnswer(inv -> {
                Callback cb = inv.getArgument(1);
                cb.onCompletion(meta, null);
                return CompletableFuture.completedFuture(meta);
            }).when(raw).send(any(ProducerRecord.class), any(Callback.class));

            decorate(raw).send(record(), null); // null callback — не должно быть NPE

            assertThat(MetricsTestSupport.findCounter(registry, "platform_producer_messages_total",
                    MetricTags.TOPIC, TOPIC,
                    MetricTags.CLIENT_ID, CLIENT_ID,
                    MetricTags.RESULT, MetricTags.RESULT_SUCCESS).count()).isEqualTo(1.0);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. Путь без Callback (TimingFuture)
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Без Callback (TimingFuture)")
    class WithoutCallback {

        @SuppressWarnings("unchecked")
        private void stubNoCallbackSend(Producer<Object, Object> raw, RecordMetadata meta, RuntimeException error) {
            doAnswer(inv -> {
                Callback cb = inv.getArgument(1);
                cb.onCompletion(error == null ? meta : null, error);
                return error == null
                        ? CompletableFuture.completedFuture(meta)
                        : CompletableFuture.failedFuture(error);
            }).when(raw).send(any(ProducerRecord.class), any(Callback.class));
        }

        @Test
        @DisplayName("result=success + lag_seconds при async completion без Future.get()")
        @SuppressWarnings("unchecked")
        void asyncCompletionWithoutFutureGetIncrementsResultSuccessAndRecordsLag() {
            RecordMetadata meta = successMetadata();
            Producer<Object, Object> raw = mockRawProducer(CLIENT_ID);
            stubNoCallbackSend(raw, meta, null);

            decorate(raw).send(record());

            Counter counter = MetricsTestSupport.findCounter(registry, "platform_producer_messages_total",
                    MetricTags.TOPIC, TOPIC,
                    MetricTags.CLIENT_ID, CLIENT_ID,
                    MetricTags.RESULT, MetricTags.RESULT_SUCCESS);
            assertThat(counter.count()).isEqualTo(1.0);

            Timer lagTimer = MetricsTestSupport.findTimer(registry, "platform_producer_lag_seconds",
                    MetricTags.TOPIC, TOPIC,
                    MetricTags.CLIENT_ID, CLIENT_ID);
            assertThat(lagTimer.count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("result=failure при async completion без Future.get(); lag_seconds не записывается")
        @SuppressWarnings("unchecked")
        void asyncCompletionFailureWithoutFutureGetIncrementsResultFailure() {
            RuntimeException brokerError = new RuntimeException("broker unavailable");
            Producer<Object, Object> raw = mockRawProducer(CLIENT_ID);
            stubNoCallbackSend(raw, null, brokerError);

            decorate(raw).send(record());

            Counter counter = MetricsTestSupport.findCounter(registry, "platform_producer_messages_total",
                    MetricTags.TOPIC, TOPIC,
                    MetricTags.CLIENT_ID, CLIENT_ID,
                    MetricTags.RESULT, MetricTags.RESULT_FAILURE);
            assertThat(counter.count()).isEqualTo(1.0);

            assertThat(registry.find("platform_producer_lag_seconds")
                    .tags(MetricTags.TOPIC, TOPIC, MetricTags.CLIENT_ID, CLIENT_ID)
                    .timer()).isNull();
        }

        @Test
        @DisplayName("async completion + Future.get() не double-count")
        @SuppressWarnings("unchecked")
        void asyncCompletionAndFutureGetDoNotDoubleCount() throws Exception {
            RecordMetadata meta = successMetadata();
            Producer<Object, Object> raw = mockRawProducer(CLIENT_ID);
            stubNoCallbackSend(raw, meta, null);

            Future<RecordMetadata> future = decorate(raw).send(record());
            future.get();

            Counter counter = MetricsTestSupport.findCounter(registry, "platform_producer_messages_total",
                    MetricTags.TOPIC, TOPIC,
                    MetricTags.CLIENT_ID, CLIENT_ID,
                    MetricTags.RESULT, MetricTags.RESULT_SUCCESS);
            assertThat(counter.count()).isEqualTo(1.0);

            Timer lagTimer = MetricsTestSupport.findTimer(registry, "platform_producer_lag_seconds",
                    MetricTags.TOPIC, TOPIC,
                    MetricTags.CLIENT_ID, CLIENT_ID);
            assertThat(lagTimer.count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("result=success + lag_seconds при успешном Future.get()")
        @SuppressWarnings("unchecked")
        void futureGetSuccessIncrementsResultSuccessAndRecordsLag() throws Exception {
            RecordMetadata meta = successMetadata();
            Producer<Object, Object> raw = mockRawProducer(CLIENT_ID);
            stubNoCallbackSend(raw, meta, null);

            Future<RecordMetadata> future = decorate(raw).send(record());
            future.get();

            Counter counter = MetricsTestSupport.findCounter(registry, "platform_producer_messages_total",
                    MetricTags.TOPIC, TOPIC,
                    MetricTags.CLIENT_ID, CLIENT_ID,
                    MetricTags.RESULT, MetricTags.RESULT_SUCCESS);
            assertThat(counter.count()).isEqualTo(1.0);

            Timer lagTimer = MetricsTestSupport.findTimer(registry, "platform_producer_lag_seconds",
                    MetricTags.TOPIC, TOPIC,
                    MetricTags.CLIENT_ID, CLIENT_ID);
            assertThat(lagTimer.count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("result=failure при Future.get() с ExecutionException; lag_seconds не записывается")
        @SuppressWarnings("unchecked")
        void futureGetFailureIncrementsResultFailure() {
            RuntimeException cause = new RuntimeException("broker down");
            Producer<Object, Object> raw = mockRawProducer(CLIENT_ID);
            stubNoCallbackSend(raw, null, cause);

            Future<RecordMetadata> future = decorate(raw).send(record());
            assertThatThrownBy(future::get).isInstanceOf(ExecutionException.class);

            Counter counter = MetricsTestSupport.findCounter(registry, "platform_producer_messages_total",
                    MetricTags.TOPIC, TOPIC,
                    MetricTags.CLIENT_ID, CLIENT_ID,
                    MetricTags.RESULT, MetricTags.RESULT_FAILURE);
            assertThat(counter.count()).isEqualTo(1.0);

            assertThat(MetricsTestSupport.findCounterOrNull(registry, "platform_producer_messages_total",
                    MetricTags.TOPIC, TOPIC,
                    MetricTags.CLIENT_ID, CLIENT_ID,
                    MetricTags.RESULT, MetricTags.RESULT_SUCCESS)).isNull();
        }

        @Test
        @DisplayName("lag_seconds имеет ненулевую длительность (реальное время отправки)")
        @SuppressWarnings("unchecked")
        void lagTimerHasPositiveDuration() throws Exception {
            RecordMetadata meta = successMetadata();
            Producer<Object, Object> raw = mockRawProducer(CLIENT_ID);
            doAnswer(inv -> {
                Thread.sleep(2);
                Callback cb = inv.getArgument(1);
                cb.onCompletion(meta, null);
                return CompletableFuture.completedFuture(meta);
            }).when(raw).send(any(ProducerRecord.class), any(Callback.class));

            decorate(raw).send(record()).get();

            Timer lagTimer = MetricsTestSupport.findTimer(registry, "platform_producer_lag_seconds",
                    MetricTags.TOPIC, TOPIC,
                    MetricTags.CLIENT_ID, CLIENT_ID);
            assertThat(lagTimer.totalTime(TimeUnit.NANOSECONDS)).isGreaterThan(0.0);
        }
    }
}
