package space.br1440.platform.metrics.kafka.producer;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import space.br1440.platform.metrics.MetricTags;
import space.br1440.platform.metrics.PlatformMetrics;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
final class TimingFuture implements Future<RecordMetadata> {

    private final Future<RecordMetadata> delegate;
    private final long startNano;
    private final String topic;
    private final String clientId;
    private final PlatformMetrics platformMetrics;

    TimingFuture(Future<RecordMetadata> delegate,
                 long startNano,
                 String topic,
                 String clientId,
                 PlatformMetrics platformMetrics) {
        this.delegate = delegate;
        this.startNano = startNano;
        this.topic = topic;
        this.clientId = clientId;
        this.platformMetrics = platformMetrics;
    }

    @Override
    public RecordMetadata get() throws InterruptedException, ExecutionException {
        try {
            RecordMetadata result = delegate.get();
            recordLag();
            return result;
        } catch (ExecutionException e) {
            incrementFailure();
            throw e;
        }
    }

    @Override
    public RecordMetadata get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        try {
            RecordMetadata result = delegate.get(timeout, unit);
            recordLag();
            return result;
        } catch (ExecutionException e) {
            incrementFailure();
            throw e;
        }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return delegate.cancel(mayInterruptIfRunning);
    }

    @Override
    public boolean isCancelled() {
        return delegate.isCancelled();
    }

    @Override
    public boolean isDone() {
        return delegate.isDone();
    }

    private void recordLag() {
        try {
            platformMetrics.kafkaProducerTotal(topic, clientId, MetricTags.RESULT_SUCCESS).increment();
            platformMetrics.kafkaProducerLag(topic, clientId)
                    .record(System.nanoTime() - startNano, TimeUnit.NANOSECONDS);
        } catch (Exception e) {
            log.debug("Failed to record platform_producer_lag_seconds (TimingFuture), topic={}", topic, e);
        }
    }

    private void incrementFailure() {
        try {
            platformMetrics.kafkaProducerTotal(topic, clientId, MetricTags.RESULT_FAILURE).increment();
        } catch (Exception e) {
            log.debug("Failed to increment platform_producer_messages_total (TimingFuture), topic={}", topic, e);
        }
    }
}
