package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import io.opentelemetry.sdk.trace.data.SpanData;

final class SpanQueue {

    static final long NONE = -1L;

    private final int maxQueueSize;
    private final int maxExportBatchSize;
    private final ArrayDeque<SpanData> queue;
    private long lastAdmittedSequence;

    SpanQueue(int maxQueueSize, int maxExportBatchSize) {
        this.maxQueueSize = maxQueueSize;
        this.maxExportBatchSize = maxExportBatchSize;
        this.queue = new ArrayDeque<>(maxQueueSize);
    }

    int size() {
        return queue.size();
    }

    boolean isEmpty() {
        return queue.isEmpty();
    }

    boolean hasBatchReady() {
        return queue.size() >= maxExportBatchSize;
    }

    long lastAdmittedSequence() {
        return lastAdmittedSequence;
    }

    long headSequence() {
        if (queue.isEmpty()) {
            return NONE;
        }

        return lastAdmittedSequence - queue.size() + 1;
    }

    long admit(SpanData snapshot) {
        long dropped = NONE;
        if (queue.size() >= maxQueueSize) {
            dropped = headSequence();
            queue.pollFirst();
        }

        lastAdmittedSequence = Math.incrementExact(lastAdmittedSequence);
        queue.offerLast(snapshot);
        return dropped;
    }

    Batch drainBatch() {
        int count = Math.min(queue.size(), maxExportBatchSize);
        if (count == 0) {
            return null;
        }

        long firstSequence = headSequence();
        List<SpanData> spans = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            spans.add(queue.pollFirst());
        }

        return new Batch(firstSequence, spans);
    }

    int drainAll() {
        int discarded = queue.size();
        queue.clear();
        return discarded;
    }

    record Batch(long firstSequence, List<SpanData> spans) {

        long lastSequence() {
            return firstSequence + spans.size() - 1;
        }
    }
}
