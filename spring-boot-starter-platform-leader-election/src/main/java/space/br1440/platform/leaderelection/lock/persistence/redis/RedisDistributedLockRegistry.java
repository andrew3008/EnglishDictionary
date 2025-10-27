package space.br1440.platform.leaderelection.lock.persistence.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import space.br1440.platform.leaderelection.lock.DistributedLock;
import space.br1440.platform.leaderelection.lock.DistributedLockRegistry;
import space.br1440.platform.leaderelection.lock.persistence.redis.resilience.CircuitBreakerAdapter;
import space.br1440.platform.leaderelection.metrics.LeaderElectionMetricsInterface;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@RequiredArgsConstructor
public class RedisDistributedLockRegistry implements DistributedLockRegistry {

    private final static int LOCKS_CACHE_CAPACITY = 100;

    private final String keyPrefix;
    private final Duration expireAfter;
    private final Duration idleBetweenTries;
    private final StringRedisTemplate redisTemplate;
    private final LeaderElectionMetricsInterface metrics;
    private final boolean metricsEnabled;
    private final CircuitBreakerAdapter circuitBreaker;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String clientId = UUID.randomUUID().toString();
    private final Lock registryLock = new ReentrantLock();
    private final Map<String, DistributedLock> locks = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, DistributedLock> eldest) {
            return size() > LOCKS_CACHE_CAPACITY;
        }
    };

    @Override
    public DistributedLock obtain(String lockKey) {
        String fullKey = buildQualifiedLockKey(lockKey);
        registryLock.lock();
        try {
            return locks.computeIfAbsent(fullKey, key ->
                    new RedisDistributedLock(
                            key,
                            clientId,
                            expireAfter,
                            idleBetweenTries,
                            redisTemplate,
                            objectMapper,
                            metrics,
                            metricsEnabled,
                            circuitBreaker
                    )
            );
        } finally {
            registryLock.unlock();
        }
    }

    private String buildQualifiedLockKey(String path) {
        return keyPrefix + ":" + path;
    }

}
