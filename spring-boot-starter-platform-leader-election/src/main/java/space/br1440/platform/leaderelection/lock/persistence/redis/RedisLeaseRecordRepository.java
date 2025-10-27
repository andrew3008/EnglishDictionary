package space.br1440.platform.leaderelection.lock.persistence.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import space.br1440.platform.leaderelection.lock.persistence.LeaseRecordRepository;
import space.br1440.platform.leaderelection.lock.persistence.model.LeaseOperationResult;
import space.br1440.platform.leaderelection.lock.persistence.model.LeaseRecord;
import space.br1440.platform.leaderelection.lock.persistence.redis.resilience.CircuitBreakerAdapter;
import space.br1440.platform.leaderelection.lock.persistence.redis.resilience.RetryExecutor;
import space.br1440.platform.leaderelection.lock.persistence.redis.script.RedisScriptExecutor;

import java.time.Duration;
import java.time.Instant;

public class RedisLeaseRecordRepository implements LeaseRecordRepository {

    private static final Duration RETRY_INITIAL_DELAY = Duration.ofMillis(100);
    private static final Duration RETRY_MAX_DELAY = Duration.ofSeconds(1);
    private static final int RETRY_ATTEMPTS = 3;
    private static final double RETRY_JITTER = 0.1;

    private final RedisScriptExecutor scriptExecutor;
    private final CircuitBreakerAdapter circuitBreaker;
    private final RetryExecutor retryExecutor;
    private final boolean metricsEnabled;

    public RedisLeaseRecordRepository(StringRedisTemplate redisTemplate,
                                      ObjectMapper objectMapper,
                                      boolean metricsEnabled,
                                      CircuitBreakerAdapter circuitBreaker) {
        this.scriptExecutor = new RedisScriptExecutor(redisTemplate, objectMapper);
        this.metricsEnabled = metricsEnabled;
        this.circuitBreaker = circuitBreaker;
        this.retryExecutor = new RetryExecutor(RETRY_ATTEMPTS, RETRY_INITIAL_DELAY, RETRY_MAX_DELAY, RETRY_JITTER);
    }

    @Override
    public boolean createRecord(String leaseKey, LeaseRecord record, Duration ttl) {
        if (!circuitBreaker.allowRequest()) {
            return false;
        }

        try {
            boolean success = retryExecutor.execute(() -> {
                LeaseOperationResult result = scriptExecutor.createLease(
                        leaseKey,
                        record.getHolderIdentity(),
                        ttl,
                        record.getLeaseDurationSeconds()
                );
                return result.isSuccess();
            }, "createRecord");

            if (success) {
                circuitBreaker.recordSuccess();
            }

            return success;
        } catch (Exception e) {
            if (metricsEnabled) {
                circuitBreaker.recordFailureWithMetrics(e);
            } else {
                circuitBreaker.recordFailure();
            }
            return false;
        }
    }

    @Override
    public LeaseRecord getCurrentRecord(String leaseKey) {
        return circuitBreaker.execute(
                () -> getRecordFromResult(scriptExecutor.getLeaseInfo(leaseKey, null)),
                () -> null
        );
    }

    @Override
    public boolean updateRecord(String leaseKey, LeaseRecord record, Duration ttl) {
        if (!circuitBreaker.allowRequest()) {
            return false;
        }

        try {
            boolean success = retryExecutor.execute(() -> {
                LeaseOperationResult result = scriptExecutor.updateLease(
                        leaseKey,
                        record.getHolderIdentity(),
                        record.getVersion(),
                        ttl,
                        record.getLeaseDurationSeconds()
                );
                return result.isSuccess();
            }, "updateRecord");

            if (success) {
                circuitBreaker.recordSuccess();
            }

            return success;
        } catch (Exception e) {
            if (metricsEnabled) {
                circuitBreaker.recordFailureWithMetrics(e);
            } else {
                circuitBreaker.recordFailure();
            }
            return false;
        }
    }

    @Override
    public boolean deleteRecord(String leaseKey, String identity) {
        if (!circuitBreaker.allowRequest()) {
            return false;
        }

        try {
            boolean success = retryExecutor.execute(() -> {
                LeaseOperationResult result = scriptExecutor.deleteLease(leaseKey, identity);
                return result.isSuccess();
            }, "deleteRecord");

            if (success) {
                circuitBreaker.recordSuccess();
            }

            return success;
        } catch (Exception e) {
            if (metricsEnabled) {
                circuitBreaker.recordFailureWithMetrics(e);
            } else {
                circuitBreaker.recordFailure();
            }
            return false;
        }
    }

    @Override
    public boolean deleteRecord(String leaseKey) {
        LeaseRecord record = getCurrentRecord(leaseKey);
        if (record == null) {
            return false;
        }

        return deleteRecord(leaseKey, record.getHolderIdentity());
    }

    public Long tryAcquireLease(String leaseKey, String identity, Duration ttl) {
        if (!circuitBreaker.allowRequest()) {
            return 0L;
        }

        try {
            return retryExecutor.execute(() -> {
                LeaseOperationResult result = scriptExecutor.createLease(
                        leaseKey,
                        identity,
                        ttl,
                        (int) ttl.getSeconds()
                );
                return result.isSuccess() ? 1L : 0L;
            }, "tryAcquireLease");
        } catch (Exception e) {
            if (metricsEnabled) {
                circuitBreaker.recordFailureWithMetrics(e);
            } else {
                circuitBreaker.recordFailure();
            }
            return 0L;
        }
    }

    public Long tryRenewLease(String leaseKey, String identity, Duration ttl) {
        if (!circuitBreaker.allowRequest()) {
            return 0L;
        }

        try {
            return retryExecutor.execute(() -> {
                LeaseRecord current = getLease(leaseKey);
                if (current == null) {
                    return 0L;
                }

                LeaseOperationResult result = scriptExecutor.updateLease(
                        leaseKey,
                        identity,
                        current.getVersion(),
                        ttl,
                        (int) ttl.getSeconds()
                );

                return result.isSuccess() ? 1L : 0L;
            }, "tryRenewLease");
        } catch (Exception e) {
            if (metricsEnabled) {
                circuitBreaker.recordFailureWithMetrics(e);
            } else {
                circuitBreaker.recordFailure();
            }
            return 0L;
        }
    }

    public Boolean releaseLease(String leaseKey, String identity) {
        return deleteRecord(leaseKey, identity);
    }

    public Long acquireOrRenewLease(String leaseKey, String identity, Duration ttl) {
        if (!circuitBreaker.allowRequest()) {
            return 0L;
        }

        try {
            return retryExecutor.execute(() -> {
                LeaseOperationResult result = scriptExecutor.acquireOrRenewLease(
                        leaseKey,
                        identity,
                        ttl,
                        (int) ttl.getSeconds()
                );
                return result.isSuccess() ? 1L : 0L;
            }, "acquireOrRenewLease");
        } catch (Exception e) {
            if (metricsEnabled) {
                circuitBreaker.recordFailureWithMetrics(e);
            } else {
                circuitBreaker.recordFailure();
            }
            return 0L;
        }
    }

    public LeaseRecord getLease(String leaseKey) {
        return getCurrentRecord(leaseKey);
    }

    private LeaseRecord getRecordFromResult(LeaseOperationResult result) {
        if (result instanceof LeaseOperationResult.Success success) {
            LeaseOperationResult.Success.LeaseData data = success.data();
            return new LeaseRecord(
                    data.holder(),
                    (data.acquireTime() != null) ? data.acquireTime() : Instant.now(),
                    (data.renewTime() != null) ? data.renewTime() : Instant.now(),
                    (data.leaseDuration() != null) ? data.leaseDuration() : 0,
                    (data.transitions() != null) ? data.transitions() : 0,
                    (data.version() != null) ? data.version().intValue() : 0
            );
        }

        return null;
    }

}
