package space.br1440.platform.metrics.kafka.producer;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.*;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.springframework.lang.Nullable;
import space.br1440.platform.metrics.MetricTags;
import space.br1440.platform.metrics.PlatformMetrics;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Slf4j
final class DecoratedProducer implements Producer<Object, Object> {

    private final Producer<Object, Object> delegate;
    private final PlatformMetrics platformMetrics;
    private final ProducerClientIdResolver clientIdResolver;

    DecoratedProducer(Producer<Object, Object> delegate, PlatformMetrics platformMetrics) {
        this.delegate = delegate;
        this.platformMetrics = platformMetrics;
        this.clientIdResolver = new ProducerClientIdResolver(delegate);
    }

    @Override
    public Future<RecordMetadata> send(ProducerRecord<Object, Object> record) {
        String topic = record.topic();
        String clientId = clientIdResolver.resolve();
        long startNano = System.nanoTime();
        Future<RecordMetadata> future = delegate.send(record);
        return new TimingFuture(future, startNano, topic, clientId, platformMetrics);
    }

    @Override
    public Future<RecordMetadata> send(ProducerRecord<Object, Object> record, @Nullable Callback callback) {
        String topic = record.topic();
        String clientId = clientIdResolver.resolve();
        long startNano = System.nanoTime();
        return delegate.send(record, (metadata, exception) -> {
            try {
                if (exception != null) {
                    incrementResult(topic, clientId, MetricTags.RESULT_FAILURE);
                } else {
                    incrementResult(topic, clientId, MetricTags.RESULT_SUCCESS);
                    recordLag(topic, clientId, System.nanoTime() - startNano);
                }
            } catch (Exception metricEx) {
                log.debug("Failed to record producer metric, topic={}", topic, metricEx);
            } finally {
                if (callback != null) {
                    callback.onCompletion(metadata, exception);
                }
            }
        });
    }

    private void incrementResult(String topic, String clientId, String result) {
        try {
            platformMetrics.kafkaProducerTotal(topic, clientId, result).increment();
        } catch (Exception e) {
            log.debug("Failed to increment platform_producer_messages_total, topic={}", topic, e);
        }
    }

    private void recordLag(String topic, String clientId, long durationNanos) {
        try {
            platformMetrics.kafkaProducerLag(topic, clientId)
                    .record(durationNanos, TimeUnit.NANOSECONDS);
        } catch (Exception e) {
            log.debug("Failed to record platform_producer_lag_seconds, topic={}", topic, e);
        }
    }

    @Override
    public void initTransactions() {
        delegate.initTransactions();
    }

    @Override
    public void beginTransaction() throws ProducerFencedException {
        delegate.beginTransaction();
    }

    @Override
    public void sendOffsetsToTransaction(Map<TopicPartition, OffsetAndMetadata> offsets,
                                         ConsumerGroupMetadata groupMetadata) throws ProducerFencedException {
        delegate.sendOffsetsToTransaction(offsets, groupMetadata);
    }

    /**
     * Устаревший API kafka-clients ({@code String consumerGroupId}); оставлен для совместимости со старыми клиентами.
     * Удалить при отказе от соответствующего метода в {@link Producer} (обычно после поднятия версии kafka-clients).
     */
    @Override
    @SuppressWarnings("deprecation")
    public void sendOffsetsToTransaction(Map<TopicPartition, OffsetAndMetadata> offsets,
                                         String consumerGroupId) throws ProducerFencedException {
        delegate.sendOffsetsToTransaction(offsets, consumerGroupId);
    }

    @Override
    public void commitTransaction() throws ProducerFencedException {
        delegate.commitTransaction();
    }

    @Override
    public void abortTransaction() throws ProducerFencedException {
        delegate.abortTransaction();
    }

    @Override
    public List<PartitionInfo> partitionsFor(String topic) {
        return delegate.partitionsFor(topic);
    }

    @Override
    public Map<MetricName, ? extends Metric> metrics() {
        return delegate.metrics();
    }

    @Override
    public void flush() {
        delegate.flush();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public void close(Duration timeout) {
        delegate.close(timeout);
    }

    @Override
    public Uuid clientInstanceId(Duration timeout) {
        return delegate.clientInstanceId(timeout);
    }
}
