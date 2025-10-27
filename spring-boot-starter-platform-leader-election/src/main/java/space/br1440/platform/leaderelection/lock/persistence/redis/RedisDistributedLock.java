package space.br1440.platform.leaderelection.lock.persistence.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.redis.core.StringRedisTemplate;
import space.br1440.platform.leaderelection.lock.DistributedLock;
import space.br1440.platform.leaderelection.lock.persistence.model.LeaseRecord;
import space.br1440.platform.leaderelection.lock.persistence.redis.resilience.CircuitBreakerAdapter;
import space.br1440.platform.leaderelection.metrics.LeaderElectionMetricsInterface;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@ToString
@Getter
public class RedisDistributedLock implements DistributedLock {

    private static final double TRY_LOCK_JITTER_FACTOR = 0.1;

    private final String leaseKey;
    private final String identity;
    private final Duration defaultTtl;
    private final Duration idleBetweenTries;
    private final RedisLeaseRecordRepository leaseRepository;
    private final LeaderElectionMetricsInterface metrics;
    private final CircuitBreakerAdapter circuitBreaker;

    private volatile long lockedAt = 0;
    private volatile boolean acquired = false;
    private final ReentrantLock localLock = new ReentrantLock();

    public RedisDistributedLock(String leaseKey,
                                String identity,
                                Duration defaultTtl,
                                Duration idleBetweenTries,
                                StringRedisTemplate redisTemplate,
                                ObjectMapper objectMapper,
                                LeaderElectionMetricsInterface metrics,
                                boolean metricsEnabled,
                                CircuitBreakerAdapter circuitBreaker) {
        this.leaseKey = leaseKey;
        this.identity = identity;
        this.defaultTtl = defaultTtl;
        this.idleBetweenTries = idleBetweenTries;
        this.leaseRepository = new RedisLeaseRecordRepository(redisTemplate, objectMapper, metricsEnabled, circuitBreaker);
        this.metrics = metrics;
        this.circuitBreaker = circuitBreaker;
    }

    public RedisDistributedLock(String leaseKey,
                                String identity,
                                Duration defaultTtl,
                                Duration idleBetweenTries,
                                RedisLeaseRecordRepository leaseRepository,
                                LeaderElectionMetricsInterface metrics,
                                CircuitBreakerAdapter circuitBreaker) {
        this.leaseKey = leaseKey;
        this.identity = identity;
        this.defaultTtl = defaultTtl;
        this.idleBetweenTries = idleBetweenTries;
        this.leaseRepository = leaseRepository;
        this.metrics = metrics;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public void lock() {
        try {
            lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Lock acquisition interrupted", e);
        }
    }

    @Override
    public void lock(Duration ttl) throws InterruptedException {
        lockInterruptibly(leaseKey, identity, ttl);
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
        lockInterruptibly(leaseKey, identity, defaultTtl);
    }

    @Override
    public boolean tryLock() {
        if (!circuitBreaker.allowRequest()) {
            return false;
        }

        return tryLock(leaseKey, identity, defaultTtl);
    }

    @Override
    public boolean tryLock(long time, @NotNull TimeUnit unit) throws InterruptedException {
        long startTime = System.nanoTime();
        long timeoutNanos = unit.toNanos(time);
        long deadline = startTime + timeoutNanos;

        if (!localLock.tryLock(timeoutNanos, TimeUnit.NANOSECONDS)) {
            metrics.recordFailedAttempt(leaseKey, "LocalLockTimeout");
            return false;
        }

        try {
            if (acquired && localLock.getHoldCount() > 1) {
                return true;
            }

            while (true) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    metrics.recordFailedAttempt(leaseKey, "DistributedLockTimeout");
                    return false;
                }

                if (Thread.interrupted()) {
                    throw new InterruptedException("Lock acquisition interrupted");
                }

                if (tryAcquireLease(leaseKey, identity, defaultTtl)) {
                    markAcquired();
                    return true;
                }

                long sleepTimeMs = calculateSleepWithJitter(remainingNanos);
                if (sleepTimeMs <= 0) {
                    metrics.recordFailedAttempt(leaseKey, "DistributedLockTimeout");
                    return false;
                }

                Thread.sleep(sleepTimeMs);
            }
        } finally {
            if (!acquired) {
                localLock.unlock();
            }
        }
    }

    @Override
    public void unlock() {
        unlock(leaseKey, localLock);
    }

    @Override
    public @NotNull Condition newCondition() {
        throw new UnsupportedOperationException("Conditions are not supported for distributed locks");
    }

    @Override
    public boolean createRecord(LeaseRecord record) {
        return leaseRepository.createRecord(leaseKey, record, defaultTtl);
    }

    @Override
    public boolean updateRecord(LeaseRecord record) {
        return leaseRepository.updateRecord(leaseKey, record, defaultTtl);
    }

    public LeaseRecord getCurrentRecord() {
        return leaseRepository.getCurrentRecord(leaseKey);
    }

    public boolean isNotAcquired() {
        return !acquired;
    }

    public boolean isLeader(String candidateIdentity) {
        if (!acquired || candidateIdentity == null) {
            return false;
        }

        LeaseRecord record = getCurrentRecord();
        return record != null && candidateIdentity.equals(record.getHolderIdentity());
    }

    private void lockInterruptibly(String leaseKey, String identity, Duration ttl) throws InterruptedException {
        localLock.lockInterruptibly();
        try {
            if (acquired && localLock.getHoldCount() > 1) {
                return;
            }

            while (!acquired) {
                if (tryAcquireLease(leaseKey, identity, ttl)) {
                    markAcquired();
                    return;
                }
                sleepWithJitter();
            }
        } catch (InterruptedException e) {
            if (!acquired) {
                localLock.unlock();
            }
            throw e;
        }
    }

    private boolean tryLock(String leaseKey, String identity, Duration ttl) {
        if (!localLock.tryLock()) {
            return false;
        }

        try {
            if (acquired && localLock.getHoldCount() > 1) {
                return true;
            }

            if (tryAcquireLease(leaseKey, identity, ttl)) {
                markAcquired();
                return true;
            }
            return false;
        } finally {
            if (!acquired) {
                localLock.unlock();
            }
        }
    }

    private void unlock(String leaseKey, ReentrantLock localLock) {
        if (!localLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("You do not own lock at " + leaseKey);
        }

        if (localLock.getHoldCount() > 1) {
            localLock.unlock();
            return;
        }

        boolean wasAcquired = acquired;
        try {
            if (wasAcquired) {
                leaseRepository.deleteRecord(leaseKey);
                metrics.recordLeadershipRevoked(leaseKey);
            }
        } catch (Exception e) {
            metrics.recordFailedAttempt(leaseKey, e.getClass().getSimpleName());
        } finally {
            if (wasAcquired) {
                markReleased();
            }
            localLock.unlock();
        }
    }

    private boolean tryAcquireLease(String leaseKey, String identity, Duration ttl) {
        if (!circuitBreaker.allowRequest()) {
            return false;
        }

        LeaseRecord record = new LeaseRecord(
                identity,
                Instant.now(),
                Instant.now(),
                (int) ttl.getSeconds(),
                0,
                1
        );
        return leaseRepository.createRecord(leaseKey, record, ttl);
    }

    private void sleepWithJitter() throws InterruptedException {
        long baseDelayMs = idleBetweenTries.toMillis();
        long jitterMs = (long) (baseDelayMs * TRY_LOCK_JITTER_FACTOR * Math.random());
        Thread.sleep(baseDelayMs + jitterMs);
    }

    private long calculateSleepWithJitter(long remainingNanos) {
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        long baseDelayMs = idleBetweenTries.toMillis();

        if (remainingMs < baseDelayMs) {
            return Math.max(0, remainingMs - 5);
        }

        long jitterMs = (long) (baseDelayMs * TRY_LOCK_JITTER_FACTOR * Math.random());
        long sleepTimeMs = baseDelayMs + jitterMs;
        return Math.min(sleepTimeMs, remainingMs - 5);
    }

    private void markAcquired() {
        this.acquired = true;
        this.lockedAt = System.currentTimeMillis();
    }

    private void markReleased() {
        this.acquired = false;
        this.lockedAt = 0;
    }

}
