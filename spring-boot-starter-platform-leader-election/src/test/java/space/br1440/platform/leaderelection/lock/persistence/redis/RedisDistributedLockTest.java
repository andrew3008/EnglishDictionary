package space.br1440.platform.leaderelection.lock.persistence.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import space.br1440.platform.leaderelection.lock.persistence.model.LeaseRecord;
import space.br1440.platform.leaderelection.lock.persistence.redis.resilience.CircuitBreaker;
import space.br1440.platform.leaderelection.lock.persistence.redis.resilience.CircuitBreakerAdapter;
import space.br1440.platform.leaderelection.lock.persistence.redis.resilience.CircuitBreakerFactory;
import space.br1440.platform.leaderelection.lock.persistence.redis.resilience.CircuitBreakerState;
import space.br1440.platform.leaderelection.metrics.LeaderElectionMetricsInterface;
import space.br1440.platform.leaderelection.metrics.NoOpLeaderElectionMetrics;
import space.br1440.platform.leaderelection.testutil.extension.annotation.WithEmbeddedRedis;
import space.br1440.platform.leaderelection.testutil.extension.container.EmbeddedRedisServer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@WithEmbeddedRedis
@DisplayName("RedisDistributedLock Race Condition Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisDistributedLockTest {

    private static final String TEST_LEASE_KEY = "test:lock:race-condition";
    private static final Duration TEST_TTL = Duration.ofSeconds(30);
    private static final Duration IDLE_BETWEEN_TRIES = Duration.ofMillis(50);

    private StringRedisTemplate redisTemplate;
    private ObjectMapper objectMapper;
    private LeaderElectionMetricsInterface metrics;

    @BeforeEach
    void setUp(EmbeddedRedisServer redisServer) {
        this.redisTemplate = redisServer.createStringRedisTemplate();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.findAndRegisterModules();
        this.metrics = new NoOpLeaderElectionMetrics();
    }

    @Nested
    @DisplayName("1. Race Condition и Версионирование")
    @Order(1)
    class RaceConditionTests {

        @Test
        @DisplayName("Версионирование должно предотвратить split-brain при concurrent tryLock")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void shouldPreventSplitBrainUsingOptimisticLockingOnConcurrentTryLock() throws Exception {
            // Given: два lock'а с разными identities
            String identity1 = "instance-1";
            String identity2 = "instance-2";

            CircuitBreakerAdapter circuitBreaker1 = CircuitBreakerFactory.create(5, Duration.ofSeconds(30));
            CircuitBreakerAdapter circuitBreaker2 = CircuitBreakerFactory.create(5, Duration.ofSeconds(30));

            RedisDistributedLock lock1 = new RedisDistributedLock(
                    TEST_LEASE_KEY,
                    identity1,
                    TEST_TTL,
                    IDLE_BETWEEN_TRIES,
                    redisTemplate,
                    objectMapper,
                    metrics,
                    true,
                    circuitBreaker1
            );

            RedisDistributedLock lock2 = new RedisDistributedLock(
                    TEST_LEASE_KEY,
                    identity2,
                    TEST_TTL,
                    IDLE_BETWEEN_TRIES,
                    redisTemplate,
                    objectMapper,
                    metrics,
                    true,
                    circuitBreaker2
            );

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch readyLatch = new CountDownLatch(2);

            AtomicReference<Boolean> result1 = new AtomicReference<>();
            AtomicReference<Boolean> result2 = new AtomicReference<>();
            AtomicReference<Exception> exception1 = new AtomicReference<>();
            AtomicReference<Exception> exception2 = new AtomicReference<>();

            CompletableFuture<Void> future1 = CompletableFuture.runAsync(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    result1.set(lock1.tryLock());
                } catch (Exception e) {
                    exception1.set(e);
                }
            });

            CompletableFuture<Void> future2 = CompletableFuture.runAsync(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    result2.set(lock2.tryLock());
                } catch (Exception e) {
                    exception2.set(e);
                }
            });

            // When: оба instance пытаются захватить lock одновременно
            boolean allReady = readyLatch.await(5, TimeUnit.SECONDS);
            assertThat(allReady).as("Оба thread должны быть готовы").isTrue();

            startLatch.countDown();

            CompletableFuture.allOf(future1, future2).get(10, TimeUnit.SECONDS);

            // Then: ровно один instance должен захватить lock
            assertThat(exception1.get()).as("Instance 1 не должен выбросить exception").isNull();
            assertThat(exception2.get()).as("Instance 2 не должен выбросить exception").isNull();

            assertThat(result1.get() ^ result2.get())
                    .as("Ровно один instance должен захватить lock (XOR condition)")
                    .isTrue();

            int successCount = (result1.get() ? 1 : 0) + (result2.get() ? 1 : 0);
            assertThat(successCount)
                    .as("Ровно один instance должен захватить lock")
                    .isEqualTo(1);

            // И LeaseRecord должен содержать корректные данные winner'а
            RedisDistributedLock winnerLock = result1.get() ? lock1 : lock2;
            String expectedWinner = result1.get() ? identity1 : identity2;
            
            LeaseRecord recordInRedis = winnerLock.getCurrentRecord();

            assertThat(recordInRedis)
                    .as("LeaseRecord должен существовать в Redis")
                    .isNotNull();

            assertThat(recordInRedis.getHolderIdentity())
                    .as("holderIdentity должен быть winner instance")
                    .isEqualTo(expectedWinner);

            assertThat(recordInRedis.getVersion())
                    .as("Version должна быть 1 для первого lock acquisition")
                    .isEqualTo(1);

            assertThat(recordInRedis.getLeaderTransitions())
                    .as("LeaderTransitions должна быть 0 для первого лидера")
                    .isEqualTo(0);
        }

        @Test
        @DisplayName("Должен предотвратить split-brain при экстремальном race condition (10 instances)")
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        void shouldPreventSplitBrainUnderExtremeRaceCondition() throws Exception {
            // Given: 10 lock'ов с разными identities
            int instanceCount = 10;
            List<RedisDistributedLock> locks = new ArrayList<>();

            for (int i = 0; i < instanceCount; i++) {
                String identity = "race-instance-" + i;

                locks.add(new RedisDistributedLock(
                        TEST_LEASE_KEY,
                        identity,
                        TEST_TTL,
                        IDLE_BETWEEN_TRIES,
                        redisTemplate,
                        objectMapper,
                        metrics,
                        true,
                        CircuitBreakerFactory.create(5, Duration.ofSeconds(30))
                ));
            }

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch readyLatch = new CountDownLatch(instanceCount);
            List<CompletableFuture<Boolean>> futures = new ArrayList<>();

            for (int i = 0; i < instanceCount; i++) {
                final int index = i;
                CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        readyLatch.countDown();
                        startLatch.await();
                        return locks.get(index).tryLock();
                    } catch (Exception e) {
                        return false;
                    }
                });
                futures.add(future);
            }

            // When: все 10 instances одновременно пытаются захватить lock
            boolean allReady = readyLatch.await(10, TimeUnit.SECONDS);
            assertThat(allReady).as("Все 10 instances должны быть готовы").isTrue();

            startLatch.countDown();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.SECONDS);

            List<Boolean> results = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            // Then: ровно один instance должен захватить lock
            long successCount = results.stream().filter(b -> b).count();

            assertThat(successCount)
                    .as("Ровно один instance должен захватить lock")
                    .isEqualTo(1);

            // и LeaseRecord должен содержать version = 1
            RedisDistributedLock successfulLock = null;
            for (int i = 0; i < instanceCount; i++) {
                if (results.get(i)) {
                    successfulLock = locks.get(i);
                    break;
                }
            }
            
            assertThat(successfulLock).as("Успешно захвативший lock должен существовать").isNotNull();
            
            LeaseRecord record = successfulLock.getCurrentRecord();
            assertThat(record).as("LeaseRecord должен существовать в Redis").isNotNull();
            assertThat(record.getVersion()).as("Version должна быть 1 для первого захвата").isEqualTo(1);
        }

        @Test
        @DisplayName("Версионирование должно предотвратить concurrent renewal от двух holders")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void shouldPreventConcurrentRenewalUsingOptimisticLocking() {
            // Given: initial lock захвачен initial-holder
            String initialHolder = "initial-holder";

            RedisDistributedLock initialLock = new RedisDistributedLock(
                    TEST_LEASE_KEY,
                    initialHolder,
                    TEST_TTL,
                    IDLE_BETWEEN_TRIES,
                    redisTemplate,
                    objectMapper,
                    metrics,
                    true,
                    CircuitBreakerFactory.create(5, Duration.ofSeconds(30))
            );

            boolean acquired = initialLock.tryLock();
            assertThat(acquired).as("Initial lock должен быть захвачен").isTrue();

            // Ждём немного, чтобы запись появилась в Redis
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }

            LeaseRecord initialRecord = initialLock.getCurrentRecord();
            assertThat(initialRecord).as("LeaseRecord должен существовать в Redis").isNotNull();
            int initialVersion = initialRecord.getVersion();

            // When: attacker'ы пытаются захватить lock
            String attacker1 = "attacker-1";
            String attacker2 = "attacker-2";

            RedisDistributedLock attackerLock1 = new RedisDistributedLock(
                    TEST_LEASE_KEY,
                    attacker1,
                    TEST_TTL,
                    IDLE_BETWEEN_TRIES,
                    redisTemplate,
                    objectMapper,
                    metrics,
                    true,
                    CircuitBreakerFactory.create(5, Duration.ofSeconds(30))
            );

            RedisDistributedLock attackerLock2 = new RedisDistributedLock(
                    TEST_LEASE_KEY,
                    attacker2,
                    TEST_TTL,
                    IDLE_BETWEEN_TRIES,
                    redisTemplate,
                    objectMapper,
                    metrics,
                    true,
                    CircuitBreakerFactory.create(5, Duration.ofSeconds(30))
            );

            boolean attacker1Success = attackerLock1.tryLock();
            boolean attacker2Success = attackerLock2.tryLock();

            // Then: attacker'ы НЕ должны захватить lock
            assertThat(attacker1Success).as("Attacker 1 НЕ должен захватить lock").isFalse();
            assertThat(attacker2Success).as("Attacker 2 НЕ должен захватить lock").isFalse();

            // И holderIdentity и version должны остаться прежними
            LeaseRecord finalRecord = initialLock.getCurrentRecord();
            assertThat(finalRecord).as("LeaseRecord должен существовать после атаки").isNotNull();
            assertThat(finalRecord.getHolderIdentity())
                    .as("holderIdentity должен остаться initial holder")
                    .isEqualTo(initialHolder);

            assertThat(finalRecord.getVersion())
                    .as("Version должна быть такой же (attackers не смогли обновить)")
                    .isEqualTo(initialVersion);

            initialLock.unlock();
        }
    }

    @Nested
    @DisplayName("2. Version Increment Logic")
    @Order(2)
    class VersionIncrementTests {

        @Test
        @DisplayName("Version должна инкрементироваться при каждом updateRecord")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void shouldIncrementVersionOnEachUpdate() {
            // Given: lock захвачен
            String holder = "version-test-holder";

            RedisDistributedLock lock = new RedisDistributedLock(
                    TEST_LEASE_KEY,
                    holder,
                    TEST_TTL,
                    IDLE_BETWEEN_TRIES,
                    redisTemplate,
                    objectMapper,
                    metrics,
                    true,
                    CircuitBreakerFactory.create(5, Duration.ofSeconds(30))
            );

            boolean acquired = lock.tryLock();
            assertThat(acquired).as("Lock должен быть захвачен").isTrue();

            // Ждём немного, чтобы запись появилась в Redis
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            
            // Проверяем, что запись появилась в Redis, используя getCurrentRecord()
            LeaseRecord record1 = lock.getCurrentRecord();
            assertThat(record1).as("LeaseRecord должен существовать в Redis").isNotNull();

            // When: обновляем запись 5 раз
            for (int i = 0; i < 5; i++) {
                LeaseRecord currentRecord = lock.getCurrentRecord();
                assertThat(currentRecord).as("LeaseRecord должен существовать перед update").isNotNull();
                int currentVersion = currentRecord.getVersion();
                int expectedNextVersion = currentVersion + 1;
                
                LeaseRecord updatedRecord = new LeaseRecord(
                        holder,
                        record1.getAcquireTime(),
                        java.time.Instant.now(),
                        (int) TEST_TTL.getSeconds(),
                        currentRecord.getLeaderTransitions(),
                        currentVersion
                );

                boolean updated = lock.updateRecord(updatedRecord);
                assertThat(updated).as("Update " + (i + 1) + " должен быть успешен").isTrue();

                // Then: версия должна инкрементироваться
                LeaseRecord recordAfterUpdate = lock.getCurrentRecord();
                assertThat(recordAfterUpdate).as("LeaseRecord после update должен существовать").isNotNull();
                assertThat(recordAfterUpdate.getVersion())
                        .as("Version должна инкрементироваться")
                        .isEqualTo(expectedNextVersion);
            }

            lock.unlock();
        }

        @Test
        @DisplayName("updateRecord должен fail при неправильной version (stale data)")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void shouldFailUpdateWithStaleVersion() {
            // Given: lock захвачен
            String holder = "stale-version-holder";

            RedisDistributedLock lock = new RedisDistributedLock(
                    TEST_LEASE_KEY,
                    holder,
                    TEST_TTL,
                    IDLE_BETWEEN_TRIES,
                    redisTemplate,
                    objectMapper,
                    metrics,
                    true,
                    CircuitBreakerFactory.create(5, Duration.ofSeconds(30))
            );

            boolean acquired = lock.tryLock();
            assertThat(acquired).as("Lock должен быть захвачен").isTrue();

            // Ждём немного, чтобы запись появилась в Redis
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }

            LeaseRecord currentRecord = lock.getCurrentRecord();
            assertThat(currentRecord).as("LeaseRecord должен существовать").isNotNull();
            int currentVersion = currentRecord.getVersion();

            // When: пытаемся обновить с устаревшей версией
            LeaseRecord staleRecord = new LeaseRecord(
                    holder,
                    currentRecord.getAcquireTime(),
                    java.time.Instant.now(),
                    (int) TEST_TTL.getSeconds(),
                    currentRecord.getLeaderTransitions(),
                    currentVersion - 1  // Устаревшая версия
            );

            boolean updated = lock.updateRecord(staleRecord);

            // Then: update должен fail и version не должна измениться
            assertThat(updated)
                    .as("Update с устаревшей version должен fail")
                    .isFalse();

            LeaseRecord recordAfterFailedUpdate = lock.getCurrentRecord();
            assertThat(recordAfterFailedUpdate).as("LeaseRecord после failed update должен существовать").isNotNull();
            assertThat(recordAfterFailedUpdate.getVersion())
                    .as("Version не должна измениться после failed update")
                    .isEqualTo(currentVersion);

            lock.unlock();
        }
    }

    @Nested
    @DisplayName("3. Thread Safety")
    @Order(3)
    class ThreadSafetyTests {

        @Test
        @DisplayName("Должен безопасно обрабатывать concurrent tryLock от одного instance")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void shouldHandleConcurrentTryLockFromSameInstance() throws Exception {
            String holder = "thread-safety-holder";

            RedisDistributedLock lock = new RedisDistributedLock(
                    TEST_LEASE_KEY,
                    holder,
                    TEST_TTL,
                    IDLE_BETWEEN_TRIES,
                    redisTemplate,
                    objectMapper,
                    metrics,
                    true,
                    CircuitBreakerFactory.create(5, Duration.ofSeconds(30))
            );

            int threadCount = 10;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch readyLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            List<CompletableFuture<Boolean>> futures = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        readyLatch.countDown();
                        startLatch.await();
                        boolean success = lock.tryLock();
                        if (success) {
                            successCount.incrementAndGet();
                        }
                        return success;
                    } catch (Exception e) {
                        return false;
                    }
                });
                futures.add(future);
            }

            //noinspection ResultOfMethodCallIgnored
            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(20, TimeUnit.SECONDS);

            assertThat(successCount.get())
                    .as("Только один thread должен захватить lock")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("4. Circuit Breaker Integration")
    @Order(4)
    class CircuitBreakerTests {

        @Test
        @DisplayName("Должен блокировать tryLock при OPEN circuit breaker")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void shouldBlockTryLockWhenCircuitBreakerIsOpen() {
            String holder = "circuit-breaker-test";
            
            CircuitBreakerAdapter circuitBreaker = CircuitBreakerFactory.create(1, Duration.ofSeconds(30));

            CircuitBreaker breaker = (CircuitBreaker) circuitBreaker;

            for (int i = 0; i < 5; i++) {
                circuitBreaker.recordFailure();
            }

            assertThat(breaker.getState())
                    .as("Circuit Breaker должен быть OPEN")
                    .isEqualTo(CircuitBreakerState.OPEN);

            RedisDistributedLock lock = new RedisDistributedLock(
                    TEST_LEASE_KEY,
                    holder,
                    TEST_TTL,
                    IDLE_BETWEEN_TRIES,
                    redisTemplate,
                    objectMapper,
                    metrics,
                    true,
                    circuitBreaker
            );

            boolean acquired = lock.tryLock();

            assertThat(acquired)
                    .as("tryLock должен вернуть false при OPEN circuit breaker")
                        .isFalse();
        }
    }

}
