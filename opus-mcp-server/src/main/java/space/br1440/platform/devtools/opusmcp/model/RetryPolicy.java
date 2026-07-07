package space.br1440.platform.devtools.opusmcp.model;

import java.util.Random;
import java.util.Set;

/**
 * Bounded retry with exponential backoff and jitter for transient model-provider failures.
 *
 * <p>Retries only safe transient conditions: network timeouts, connection/IO errors, and HTTP
 * 408/429/500/502/503/504. Validation, auth, not-found, and not-allowlisted failures are never
 * retried. Never retries indefinitely.
 */
public final class RetryPolicy {

    /** Pluggable sleep seam for deterministic tests. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** A single attempt that may throw {@link OpusClientException}. */
    @FunctionalInterface
    public interface Attempt<T> {
        T run() throws OpusClientException;
    }

    private static final Set<Integer> RETRYABLE_HTTP = Set.of(408, 429, 500, 502, 503, 504);

    private final int maxAttempts;
    private final long baseDelayMs;
    private final long maxDelayMs;
    private final Sleeper sleeper;
    private final Random random;

    public RetryPolicy(int maxAttempts, long baseDelayMs, long maxDelayMs) {
        this(maxAttempts, baseDelayMs, maxDelayMs, defaultSleeper(), new Random());
    }

    RetryPolicy(int maxAttempts, long baseDelayMs, long maxDelayMs, Sleeper sleeper, Random random) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseDelayMs = Math.max(0, baseDelayMs);
        this.maxDelayMs = Math.max(this.baseDelayMs, maxDelayMs);
        this.sleeper = sleeper;
        this.random = random;
    }

    private static Sleeper defaultSleeper() {
        return Thread::sleep;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public <T> T execute(Attempt<T> attempt) throws OpusClientException {
        int tries = 0;
        while (true) {
            tries++;
            try {
                return attempt.run();
            } catch (OpusClientException e) {
                if (tries >= maxAttempts || !isRetryable(e)) {
                    throw e;
                }
                try {
                    sleeper.sleep(backoffMillis(tries));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    static boolean isRetryable(OpusClientException e) {
        return switch (e.reason()) {
            case TIMEOUT, NETWORK_ERROR -> true;
            case HTTP_ERROR -> RETRYABLE_HTTP.contains(e.httpStatus());
            default -> false;
        };
    }

    long backoffMillis(int attemptNumber) {
        long exp = baseDelayMs << Math.min(attemptNumber - 1, 30);
        long capped = Math.min(exp, maxDelayMs);
        if (capped <= 0) {
            return 0;
        }
        return capped / 2 + (long) (random.nextDouble() * (capped / 2.0));
    }
}
