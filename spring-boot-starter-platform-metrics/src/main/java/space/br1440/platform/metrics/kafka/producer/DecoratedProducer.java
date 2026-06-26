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
import space.br1440.platform.metrics.PlatformMetrics;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

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
        ProducerSendMetricsRecorder recorder = new ProducerSendMetricsRecorder(
                platformMetrics, topic, clientId, startNano);
        Future<RecordMetadata> future = delegate.send(record, (metadata, exception) -> {
            if (exception != null) {
                recorder.failure();
            } else {
                recorder.success();
            }
        });
        return new TimingFuture(future, recorder);
    }

    @Override
    public Future<RecordMetadata> send(ProducerRecord<Object, Object> record, @Nullable Callback callback) {
        String topic = record.topic();
        String clientId = clientIdResolver.resolve();
        long startNano = System.nanoTime();
        ProducerSendMetricsRecorder recorder = new ProducerSendMetricsRecorder(
                platformMetrics, topic, clientId, startNano);
        return delegate.send(record, (metadata, exception) -> {
            try {
                if (exception != null) {
                    recorder.failure();
                } else {
                    recorder.success();
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
