package space.br1440.platform.devtools.opusmcp.budget;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * Simple in-memory sliding-window rate limiter (no external dependencies).
 *
 * <p>Allows at most {@code permitsPerMinute} acquisitions within any rolling 60-second window. A
 * limit of {@code 0} (or negative) disables limiting.
 */
public final class RateLimiter {

    private static final long WINDOW_MS = 60_000L;

    private final int permitsPerMinute;
    private final LongSupplier clockMs;
    private final Deque<Long> window = new ArrayDeque<>();

    public RateLimiter(int permitsPerMinute) {
        this(permitsPerMinute, System::currentTimeMillis);
    }

    public RateLimiter(int permitsPerMinute, LongSupplier clockMs) {
        this.permitsPerMinute = permitsPerMinute;
        this.clockMs = clockMs == null ? System::currentTimeMillis : clockMs;
    }

    /** Attempts to acquire a permit. Returns {@code true} if allowed. */
    public synchronized boolean tryAcquire() {
        if (permitsPerMinute <= 0) {
            return true;
        }
        long now = clockMs.getAsLong();
        long cutoff = now - WINDOW_MS;
        while (!window.isEmpty() && window.peekFirst() <= cutoff) {
            window.pollFirst();
        }
        if (window.size() >= permitsPerMinute) {
            return false;
        }
        window.addLast(now);
        return true;
    }
}
