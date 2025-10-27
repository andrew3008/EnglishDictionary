package space.br1440.platform.leaderelection.lock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import space.br1440.platform.leaderelection.lock.persistence.redis.RedisDistributedLock;
import space.br1440.platform.leaderelection.lock.persistence.redis.RedisDistributedLockRegistry;
import space.br1440.platform.leaderelection.lock.persistence.redis.resilience.CircuitBreakerFactory;
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

import static org.assertj.core.api.Assertions.assertThat;

@WithEmbeddedRedis
@DisplayName("DistributedLockRegistry Concurrency Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DistributedLockRegistryConcurrencyTest {

    private static final String LOCK_KEY = "concurrency-test-lock";
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);
    private static final Duration IDLE_BETWEEN_TRIES = Duration.ofMillis(50);

    private StringRedisTemplate redisTemplate;
    private ObjectMapper objectMapper;
    private DistributedLockRegistry lockRegistry;

    @BeforeEach
    void setUp(EmbeddedRedisServer redisServer) {
        this.redisTemplate = redisServer.createStringRedisTemplate();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.findAndRegisterModules();
        this.lockRegistry = createLockRegistry();
    }

    @Nested
    @DisplayName("Concurrent Lock Acquisition")
    class ConcurrentLockAcquisitionTests {

        @Test
        @DisplayName("Только один поток должен захватить lock при одновременном получении через registry")
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @Order(1)
        void shouldAllowOnlyOneThreadToAcquireLockConcurrently() throws Exception {
            // Given
            int threadCount = 10;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch readyLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            List<CompletableFuture<Boolean>> futures = new ArrayList<>();

            // When: 10 потоков одновременно получают lock через registry и пытаются захватить
            for (int i = 0; i < threadCount; i++) {
                CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        readyLatch.countDown();
                        startLatch.await();

                        // Получаем lock через registry
                        DistributedLock lock = lockRegistry.obtain(LOCK_KEY);

                        // Пытаемся захватить
                        boolean acquired = lock.tryLock();

                        if (acquired) {
                            successCount.incrementAndGet();
                            // Держим lock немного
                            Thread.sleep(500);
                        }

                        return acquired;
                    } catch (Exception e) {
                        return false;
                    }
                });
                futures.add(future);
            }

            // Запускаем все потоки одновременно
            boolean allReady = readyLatch.await(10, TimeUnit.SECONDS);
            assertThat(allReady).as("Все потоки должны быть готовы").isTrue();

            startLatch.countDown();

            // Ждём завершения всех операций
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(45, TimeUnit.SECONDS);

            // Then: только один поток должен успешно захватить lock
            assertThat(successCount.get())
                    .as("Ровно один поток должен захватить lock")
                    .isEqualTo(1);

            // Cleanup
            for (CompletableFuture<Boolean> future : futures) {
                Boolean result = future.join();
                if (result != null && result) {
                    DistributedLock lock = lockRegistry.obtain(LOCK_KEY);
                    // Пытаемся освободить lock
                    try {
                        lock.unlock();
                    } catch (IllegalStateException e) {
                        // Lock может быть уже освобождён, это нормально
                    }
                }
            }
        }

        @Test
        @DisplayName("Registry должен возвращать один и тот же lock объект для одного ключа")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @Order(2)
        void shouldReturnSameLockObjectForSameKey() {
            // Given
            String lockKey = "same-key-lock";

            // When: получаем lock дважды
            DistributedLock lock1 = lockRegistry.obtain(lockKey);
            DistributedLock lock2 = lockRegistry.obtain(lockKey);

            // Then: должен быть один и тот же объект (caching)
            assertThat(lock1)
                    .as("Registry должен возвращать один и тот же lock объект")
                    .isSameAs(lock2);

            // And: оба должны указывать на один и тот же instance
            assertThat(lock1.getClass())
                    .as("Оба lock должны быть одного типа")
                    .isEqualTo(lock2.getClass());
        }

        @Test
        @DisplayName("Реентерабельность: один поток может вызывать tryLock несколько раз")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @Order(3)
        void shouldSupportReentrancyFromSameThread() {
            // Given
            String identity = "reentrant-thread";
            
            // When: первый tryLock успешен
            RedisDistributedLock redisLock = createLockForTesting("reentrancy-lock", identity);
            boolean firstAttempt = redisLock.tryLock();
            assertThat(firstAttempt).as("Первый tryLock должен быть успешным").isTrue();

            // And: второй tryLock от того же потока (reentrancy)
            boolean secondAttempt = redisLock.tryLock();
            assertThat(secondAttempt).as("Второй tryLock должен быть успешным (reentrancy)").isTrue();

            // Then
            assertThat(redisLock.isAcquired())
                    .as("Lock должен быть захвачен")
                    .isTrue();

            // Cleanup
            redisLock.unlock();
            redisLock.unlock(); // Unlock для обоих tryLock
        }

        @Test
        @DisplayName("Разные ключи не должны конфликтовать")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @Order(4)
        void shouldNotConflictWithDifferentKeys() {
            // Given
            String lockKey1 = "different-key-1";
            String lockKey2 = "different-key-2";
            String identity1 = "thread-1";
            String identity2 = "thread-2";

            RedisDistributedLock lock1 = createLockForTesting(lockKey1, identity1);
            RedisDistributedLock lock2 = createLockForTesting(lockKey2, identity2);

            // When: оба потока пытаются захватить разные ключи одновременно
            boolean acquired1 = lock1.tryLock();
            boolean acquired2 = lock2.tryLock();

            // Then: оба должны быть успешными
            assertThat(acquired1)
                    .as("Lock 1 должен быть захвачен")
                    .isTrue();

            assertThat(acquired2)
                    .as("Lock 2 должен быть захвачен")
                    .isTrue();

            // Cleanup
            lock1.unlock();
            lock2.unlock();
        }
    }

    @Nested
    @DisplayName("Lock Registry Behavior")
    class LockRegistryBehaviorTests {

        @Test
        @DisplayName("Registry должен создавать новый lock для несуществующего ключа")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @Order(1)
        void shouldCreateNewLockForNonExistentKey() {
            // Given
            String newLockKey = "new-lock-key";

            // When
            DistributedLock lock = lockRegistry.obtain(newLockKey);

            // Then
            assertThat(lock)
                    .as("Registry должен создать новый lock")
                    .isNotNull();

            assertThat(lock.getClass())
                    .as("Lock должен быть типа RedisDistributedLock")
                    .isEqualTo(RedisDistributedLock.class);
        }

        @Test
        @DisplayName("Registry должен кэшировать lock объекты по ключу")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @Order(2)
        void shouldCacheLockObjectsByKey() {
            // Given
            String key = "cache-test-key";

            // When: получаем lock несколько раз
            DistributedLock lock1 = lockRegistry.obtain(key);
            DistributedLock lock2 = lockRegistry.obtain(key);
            DistributedLock lock3 = lockRegistry.obtain(key);

            // Then: должны быть одним и тем же объектом
            assertThat(lock1).isSameAs(lock2);
            assertThat(lock2).isSameAs(lock3);
        }
    }

    private DistributedLockRegistry createLockRegistry() {
        return new RedisDistributedLockRegistry(
                "test",
                LOCK_TTL,
                IDLE_BETWEEN_TRIES,
                redisTemplate,
                new NoOpLeaderElectionMetrics(),
                false,
                CircuitBreakerFactory.create(5, Duration.ofSeconds(30))
        );
    }

    private RedisDistributedLock createLockForTesting(String lockKey, String identity) {
        return new RedisDistributedLock(
                lockKey,
                identity,
                LOCK_TTL,
                IDLE_BETWEEN_TRIES,
                redisTemplate,
                objectMapper,
                new NoOpLeaderElectionMetrics(),
                true,
                CircuitBreakerFactory.create(5, Duration.ofSeconds(30))
        );
    }

}
