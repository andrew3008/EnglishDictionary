package space.br1440.platform.tracing.autoconfigure.metrics;

import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceException;

final class CoalescingJmxObservationSource<T> {

    private final Supplier<Optional<T>> source;
    private final MonotonicJmxCounterGroupBridge.Ticker ticker;
    private final long coalescingWindowNanos;
    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile Snapshot<T> snapshot;

    CoalescingJmxObservationSource(Supplier<Optional<T>> source,
                                   MonotonicJmxCounterGroupBridge.Ticker ticker) {
        this(source, ticker, MonotonicJmxCounterGroupBridge.DEFAULT_COALESCING_WINDOW_NANOS);
    }

    CoalescingJmxObservationSource(Supplier<Optional<T>> source,
                                   MonotonicJmxCounterGroupBridge.Ticker ticker,
                                   long coalescingWindowNanos) {
        this.source = source;
        this.ticker = ticker;
        this.coalescingWindowNanos = coalescingWindowNanos;
        this.snapshot = new Snapshot<>(Optional.empty(), Long.MIN_VALUE);
    }

    Optional<T> get() {
        Snapshot<T> current = snapshot;
        long now = ticker.nanoTime();
        if (isFresh(current, now)) {
            return current.value();
        }

        if (refreshLock.tryLock()) {
            try {
                current = snapshot;

                if (!isFresh(current, ticker.nanoTime())) {
                    Optional<T> observed = readSource();
                    current = new Snapshot<>(observed, ticker.nanoTime());
                    snapshot = current;
                }
            } finally {
                refreshLock.unlock();
            }
        }

        return current.value();
    }

    private Optional<T> readSource() {
        try {
            return source.get();
        } catch (AgentReadinessCoherenceException ignored) {
            return Optional.empty();
        }
    }

    private boolean isFresh(Snapshot<T> current, long now) {
        return (current.refreshedAtNanos() != Long.MIN_VALUE) &&
                (now - current.refreshedAtNanos()) < coalescingWindowNanos;
    }

    private record Snapshot<T>(Optional<T> value, long refreshedAtNanos) {
    }
}
