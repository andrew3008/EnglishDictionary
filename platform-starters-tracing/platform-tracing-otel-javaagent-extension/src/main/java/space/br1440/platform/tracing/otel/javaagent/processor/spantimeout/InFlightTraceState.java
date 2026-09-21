package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;

final class InFlightTraceState {

    private final long startNanos;
    private volatile Object entries;

    InFlightTraceState(long startNanos) {
        this.startNanos = startNanos;
    }

    long startNanos() {
        return startNanos;
    }

    boolean contains(String spanId) {
        Object current = entries;
        if (current instanceof Tracked single) {
            return single.spanId().equals(spanId);
        }

        return (current instanceof ConcurrentHashMap<?, ?> multiple) && multiple.containsKey(spanId);
    }

    boolean addIfAbsent(Tracked tracked) {
        Object current = entries;
        if (current == null) {
            entries = tracked;
            return true;
        }

        if (current instanceof Tracked single) {
            if (single.spanId().equals(tracked.spanId())) {
                return false;
            }

            ConcurrentHashMap<String, Tracked> promoted = new ConcurrentHashMap<>();
            promoted.put(single.spanId(), single);
            promoted.put(tracked.spanId(), tracked);
            entries = promoted;
            return true;
        }

        return multiple(current).putIfAbsent(tracked.spanId(), tracked) == null;
    }

    boolean removeIfPresent(String spanId, ReadableSpan expected) {
        Object current = entries;
        if (current == null) {
            return false;
        }

        if (current instanceof Tracked single) {
            if (single.spanId().equals(spanId) && (single.span() == expected)) {
                entries = null;
                return true;
            }

            return false;
        }

        ConcurrentHashMap<String, Tracked> multiple = multiple(current);
        Tracked tracked = multiple.get(spanId);
        if ((tracked != null) && (tracked.span() == expected) && multiple.remove(spanId, tracked)) {
            collapseIfSingle(multiple);
            return true;
        }

        return false;
    }

    boolean remove(Tracked tracked) {
        Object current = entries;
        if (current == tracked) {
            entries = null;
            return true;
        }

        if (!(current instanceof ConcurrentHashMap<?, ?>)) {
            return false;
        }

        ConcurrentHashMap<String, Tracked> multiple = multiple(current);
        if (!multiple.remove(tracked.spanId(), tracked)) {
            return false;
        }

        collapseIfSingle(multiple);
        return true;
    }

    int drain() {
        Object current = entries;
        entries = null;
        if (current == null) {
            return 0;
        }

        return (current instanceof Tracked) ? 1 : multiple(current).size();
    }

    List<Tracked> records() {
        Object current = entries;
        if (current == null) {
            return List.of();
        }

        if (current instanceof Tracked single) {
            return List.of(single);
        }

        return new ArrayList<>(multiple(current).values());
    }

    int size() {
        Object current = entries;
        if (current == null) {
            return 0;
        }

        return (current instanceof Tracked) ? 1 : multiple(current).size();
    }

    boolean isEmpty() {
        return (size() == 0);
    }

    private void collapseIfSingle(ConcurrentHashMap<String, Tracked> multiple) {
        if (multiple.size() != 1) {
            return;
        }

        entries = multiple.values().iterator().next();
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, Tracked> multiple(Object current) {
        return (ConcurrentHashMap<String, Tracked>) current;
    }

    record Tracked(String spanId, ReadWriteSpan span, long startNanos) {

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
