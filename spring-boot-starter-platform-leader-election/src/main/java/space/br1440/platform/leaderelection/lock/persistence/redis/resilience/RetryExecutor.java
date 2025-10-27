package space.br1440.platform.leaderelection.lock.persistence.redis.resilience;

import space.br1440.platform.leaderelection.lock.persistence.redis.exception.RedisDBException;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public class RetryExecutor {

    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final double jitterFactor;

    public RetryExecutor(int maxAttempts, Duration baseDelay, Duration maxDelay, double jitterFactor) {
        this.maxAttempts = maxAttempts;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.jitterFactor = jitterFactor;
    }

    public <T> T execute(Supplier<T> operation, String operationName) throws RedisDBException {
        Exception lastException = null;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                return operation.get();
            } catch (Exception e) {
                lastException = e;
                if (attempt < (maxAttempts - 1)) {
                    long delay = calculateRetryDelay(attempt);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RedisDBException("Retry interrupted for " + operationName, ie);
                    }
                }
            }
        }

        throw new RedisDBException("Operation " + operationName + " failed after " + maxAttempts + " attempts", lastException);
    }

    private long calculateRetryDelay(int attempt) {
        long baseDelayMs = baseDelay.toMillis();
        long maxDelayMs = maxDelay.toMillis();

        int maxShift = Math.min(attempt, 30);
        long exponentialDelay = Math.min(baseDelayMs * (1L << maxShift), maxDelayMs);
        long jitter = (long) (exponentialDelay * jitterFactor * ThreadLocalRandom.current().nextDouble());

        return exponentialDelay + jitter;
    }

}
