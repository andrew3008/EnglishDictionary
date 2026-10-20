package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import lombok.experimental.UtilityClass;

@UtilityClass
final class EmptyEvictionPlanner {

    private static final Comparator<EvictionCandidate> OLDEST_FIRST = Comparator
            .comparingLong((EvictionCandidate candidate) -> candidate.expectedState().startNanos())
            .thenComparing(EvictionCandidate::traceId);
    private static final Comparator<EvictionCandidate> NEWEST_FIRST = OLDEST_FIRST.reversed();

    static List<EvictionCandidate> selectOldestEmpty(Iterable<Map.Entry<String, InFlightTraceState>> entries,
                                                     int batch) {
        if (batch <= 0) {
            throw new IllegalArgumentException("batch must be greater than zero");
        }

        PriorityQueue<EvictionCandidate> oldestEmpty = new PriorityQueue<>(batch, NEWEST_FIRST);
        for (Map.Entry<String, InFlightTraceState> entry : entries) {
            InFlightTraceState state = entry.getValue();
            if (!state.isEmpty()) {
                continue;
            }

            offerBounded(oldestEmpty, new EvictionCandidate(entry.getKey(), state), batch);
        }

        return List.copyOf(oldestEmpty);
    }

    private static void offerBounded(PriorityQueue<EvictionCandidate> queue, EvictionCandidate candidate, int batch) {
        if (queue.size() < batch) {
            queue.offer(candidate);
            return;
        }

        EvictionCandidate newestKept = queue.peek();
        if ((newestKept != null) && (OLDEST_FIRST.compare(candidate, newestKept) < 0)) {
            queue.poll();
            queue.offer(candidate);
        }
    }

    record EvictionCandidate(String traceId, InFlightTraceState expectedState) {
    }
}
