package space.br1440.platform.tracing.otel.javaagent.scrubbing;

import java.util.concurrent.atomic.LongAdder;

final class MonotonicCounter {

    private final LongAdder adder = new LongAdder();

    MonotonicCounter() {
    }

    public void increment() {
        adder.increment();
    }

    public void add(long delta) {
        if (delta < 0L) {
            throw new IllegalArgumentException("Scrubbing observation counters are monotonic; delta=" + delta);
        }

        if (delta > 0L) {
            adder.add(delta);
        }
    }

    public long sum() {
        return adder.sum();
    }

    @Override
    public String toString() {
        return Long.toString(adder.sum());
    }
}
