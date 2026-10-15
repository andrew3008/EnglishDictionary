package space.br1440.platform.tracing.otel.javaagent.distribution.probe;

import java.time.Duration;

record MonotonicDeadline(long expiresAtNanos) {

    static MonotonicDeadline after(Duration timeout) {
        return new MonotonicDeadline(System.nanoTime() + timeout.toNanos());
    }

    boolean hasRemaining() {
        return (System.nanoTime() - expiresAtNanos) < 0L;
    }
}
