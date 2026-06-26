package space.br1440.platform.metrics.kafka.producer;

import org.apache.kafka.clients.producer.RecordMetadata;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class TimingFuture implements Future<RecordMetadata> {

    private final Future<RecordMetadata> delegate;
    private final ProducerSendMetricsRecorder recorder;

    TimingFuture(Future<RecordMetadata> delegate, ProducerSendMetricsRecorder recorder) {
        this.delegate = delegate;
        this.recorder = recorder;
    }

    @Override
    public RecordMetadata get() throws InterruptedException, ExecutionException {
        try {
            RecordMetadata result = delegate.get();
            recorder.success();
            return result;
        } catch (ExecutionException e) {
            recorder.failure();
            throw e;
        }
    }

    @Override
    public RecordMetadata get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        try {
            RecordMetadata result = delegate.get(timeout, unit);
            recorder.success();
            return result;
        } catch (ExecutionException e) {
            recorder.failure();
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
}
