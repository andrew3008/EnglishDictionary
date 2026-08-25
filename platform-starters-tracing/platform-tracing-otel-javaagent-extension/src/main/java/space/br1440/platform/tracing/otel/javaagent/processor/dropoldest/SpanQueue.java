package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeSet;

import io.opentelemetry.sdk.trace.data.SpanData;

final class SpanQueue {

    private final int maxQueueSize;
    private final int maxExportBatchSize;
    private final ArrayDeque<SequencedSpan> queue;
    private final NavigableSet<Long> unresolvedSequences = new TreeSet<>();
    private long lastAdmittedSequence;

    SpanQueue(int maxQueueSize, int maxExportBatchSize) {
        if (maxQueueSize <= 0) {
            throw new IllegalArgumentException("maxQueueSize must be positive, got: " + maxQueueSize);
        }

        if (maxExportBatchSize <= 0) {
            throw new IllegalArgumentException("maxExportBatchSize must be positive, got: " + maxExportBatchSize);
        }

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

    boolean isResolvedUpTo(long watermark) {
        return unresolvedSequences.floor(watermark) == null;
    }

    AdmitOutcome admit(SpanData snapshot) {
        SequencedSpan dropped = null;
        if (queue.size() >= maxQueueSize) {
            dropped = Objects.requireNonNull(queue.pollFirst(),"queue.pollFirst() must not be null: queue.size() >= maxQueueSize > 0");
            unresolvedSequences.remove(dropped.sequence());
        }

        long sequence = Math.incrementExact(lastAdmittedSequence);
        lastAdmittedSequence = sequence;
        queue.offerLast(new SequencedSpan(sequence, snapshot));
        unresolvedSequences.add(sequence);

        return new AdmitOutcome(sequence, dropped);
    }

    void resolve(List<SequencedSpan> batch) {
        for (SequencedSpan span : batch) {
            unresolvedSequences.remove(span.sequence());
        }
    }

    List<SequencedSpan> drainBatch() {
        int n = Math.min(queue.size(), maxExportBatchSize);
        if (n == 0) {
            return List.of();
        }

        List<SequencedSpan> batch = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            batch.add(queue.pollFirst());
        }

        return batch;
    }

    List<SequencedSpan> drainAll() {
        if (queue.isEmpty()) {
            return List.of();
        }

        List<SequencedSpan> discarded = new ArrayList<>(queue);
        queue.clear();
        resolve(discarded);
        return discarded;
    }

    record AdmitOutcome(long sequence, SequencedSpan dropped) {
    }
}
