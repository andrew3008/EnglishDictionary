package space.br1440.platform.tracing.otel.javaagent.processor.watchdog;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;

final class TraceWatchdogState {

    private final long startNanos;
    private volatile Tracked single;
    private volatile ConcurrentHashMap<String, Tracked> multiple;

    TraceWatchdogState(long startNanos) {
        this.startNanos = startNanos;
    }

    long startNanos() {
        return startNanos;
    }

    boolean contains(String spanId) {
        Tracked currentSingle = single;
        if (currentSingle != null) {
            return currentSingle.spanId().equals(spanId);
        }

        ConcurrentHashMap<String, Tracked> current = multiple;
        return (current != null) && current.containsKey(spanId);
    }

    boolean addIfAbsent(Tracked tracked) {
        ConcurrentHashMap<String, Tracked> current = multiple;
        if (current != null) {
            return current.putIfAbsent(tracked.spanId(), tracked) == null;
        }

        if (single == null) {
            single = tracked;
            return true;
        }

        if (single.spanId().equals(tracked.spanId())) {
            return false;
        }

        ConcurrentHashMap<String, Tracked> promoted = new ConcurrentHashMap<>();
        promoted.put(single.spanId(), single);
        promoted.put(tracked.spanId(), tracked);
        multiple = promoted;
        single = null;
        return true;
    }

    boolean removeIfPresent(String spanId, ReadableSpan expected) {
        Tracked currentSingle = single;
        if (currentSingle != null) {
            if (currentSingle.spanId().equals(spanId) && currentSingle.spanRef().get() == expected) {
                single = null;
                return true;
            }

            return false;
        }

        ConcurrentHashMap<String, Tracked> current = multiple;
        if (current == null) {
            return false;
        }

        Tracked tracked = current.get(spanId);
        if ((tracked != null) && (tracked.spanRef().get() == expected) && current.remove(spanId, tracked)) {
            collapseIfSingle(current);
            return true;
        }

        return false;
    }

    boolean remove(Tracked tracked) {
        if (single == tracked) {
            single = null;
            return true;
        }

        ConcurrentHashMap<String, Tracked> current = multiple;
        if (current == null || !current.remove(tracked.spanId(), tracked)) {
            return false;
        }

        collapseIfSingle(current);
        return true;
    }

    int drain() {
        ConcurrentHashMap<String, Tracked> current = multiple;
        if (current != null) {
            multiple = null;
            single = null;
            return current.size();
        }

        if (single != null) {
            single = null;
            return 1;
        }

        return 0;
    }

    List<Tracked> records() {
        Tracked currentSingle = single;
        if (currentSingle != null) {
            return List.of(currentSingle);
        }

        ConcurrentHashMap<String, Tracked> current = multiple;
        return (current == null) ? List.of() : new ArrayList<>(current.values());
    }

    int size() {
        ConcurrentHashMap<String, Tracked> current = multiple;
        return (current == null) ? (single == null ? 0 : 1) : current.size();
    }

    boolean isEmpty() {
        return (size() == 0);
    }

    private void collapseIfSingle(ConcurrentHashMap<String, Tracked> current) {
        if (current.size() != 1) {
            return;
        }

        single = current.values().iterator().next();
        multiple = null;
    }

    record Tracked(String spanId, WeakReference<ReadWriteSpan> spanRef, long startNanos) {

        @Override
        public boolean equals(Object that) {
            if (that instanceof Tracked) {
                return (this == that);
            }

            return false;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }
}
