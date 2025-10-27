package space.br1440.platform.leaderelection.annotation;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import space.br1440.platform.leaderelection.lock.DistributedLock;
import space.br1440.platform.leaderelection.lock.DistributedLockRegistry;
import space.br1440.platform.leaderelection.lock.persistence.redis.RedisDistributedLockRegistry;
import space.br1440.platform.leaderelection.lock.persistence.redis.resilience.CircuitBreakerFactory;
import space.br1440.platform.leaderelection.metrics.NoOpLeaderElectionMetrics;
import space.br1440.platform.leaderelection.testutil.extension.LeaderElectionTestExtension;
import space.br1440.platform.leaderelection.testutil.extension.annotation.WithEmbeddedRedis;
import space.br1440.platform.leaderelection.testutil.extension.container.EmbeddedRedisServer;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WithEmbeddedRedis
@ExtendWith(LeaderElectionTestExtension.class)
@DisplayName("Интеграционные тесты @DistributedLock аннотаций")
class DistributedLockIntegrationTest {

    private static final String TEST_KEY_PREFIX = "distributed-lock-test";
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    private static final Duration IDLE_BETWEEN_TRIES = Duration.ofMillis(100);

    private DistributedLockRegistry lockRegistry;
    private TestService testService;
    private ExecutorService executorService;
    private StringRedisTemplate redisTemplate;
    private EmbeddedRedisServer redisServer;

    @BeforeEach
    void setUp(EmbeddedRedisServer redisServer) {
        this.redisServer = redisServer;
        this.redisTemplate = redisServer.createStringRedisTemplate();

        verifyRedisIsHealthy();
        redisServer.clearRedis(redisTemplate);

        this.lockRegistry = createDistributedLockRegistry();
        this.testService = new TestService();
        this.executorService = Executors.newFixedThreadPool(10);
    }

    @AfterEach
    void tearDown(EmbeddedRedisServer redisServer) {
        if (!executorService.isShutdown()) {
            executorService.shutdownNow();
        }

        redisServer.clearRedis(redisTemplate);
    }

    @Nested
    @DisplayName("Базовые тесты @DistributedLock")
    class DistributedLockBasicTests {

        @Test
        @DisplayName("Должен захватить и освободить @DistributedLock")
        void shouldAcquireAndReleaseDistributedLock() {
            // Given
            String lockName = "basic-distributed-lock";
            AtomicBoolean executed = new AtomicBoolean(false);

            // When
            DistributedLock lock = lockRegistry.obtain(lockName);
            boolean acquired = lock.tryLock();
            assertThat(acquired).as("Блокировка должна захватываться").isTrue();

            try {
                testService.basicDistributedLock();
                executed.set(true);
            } finally {
                lock.unlock();
            }

            // Then
            assertThat(executed.get())
                    .as("Задача должна выполниться под блокировкой")
                    .isTrue();
        }

        @Test
        @DisplayName("Должен обработать исключение в @DistributedLock методе")
        void shouldHandleExceptionInDistributedLockMethod() {
            // Given
            String lockName = "failing-distributed-lock";

            // When
            DistributedLock lock = lockRegistry.obtain(lockName);
            boolean acquired = lock.tryLock();
            assertThat(acquired).as("Блокировка должна захватываться").isTrue();

            // Then
            assertThatThrownBy(() -> {
                try {
                    testService.failingDistributedLock();
                } finally {
                    lock.unlock();
                }
            }).isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Test exception");
        }

        @Test
        @DisplayName("Должен освободить блокировку при исключении")
        void shouldReleaseLockOnException() {
            // Given
            String lockName = "failing-distributed-lock";

            // When
            DistributedLock lock = lockRegistry.obtain(lockName);
            boolean acquired = lock.tryLock();
            assertThat(acquired).as("Блокировка должна захватываться").isTrue();

            try {
                testService.failingDistributedLock();
            } catch (RuntimeException ignored) {
            } finally {
                lock.unlock();
            }

            // Then
            Set<String> keySet = new HashSet<>();
            try (Cursor<String> keys = redisTemplate.scan(ScanOptions.scanOptions().match("*").build())) {
                keys.forEachRemaining(keySet::add);
            }
            assertThat(keySet).as("Redis должен быть очищен после освобождения блокировки").isEmpty();
        }
    }

    @Nested
    @DisplayName("Конкурентные операции")
    class ConcurrentOperationsTests {

        @Test
        @DisplayName("Должен обеспечить единственность выполнения при множественных потоках")
        void shouldEnsureSingleExecutionWithMultipleThreads() throws Exception {
            // Given
            String lockName = "concurrent-distributed-lock";
            AtomicInteger successCount = new AtomicInteger(0);
            int threadCount = 5;

            // When - запускаем несколько потоков одновременно
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < threadCount; i++) {
                var future = executorService.submit(() -> {
                    DistributedLock lock = lockRegistry.obtain(lockName);
                    boolean acquired = lock.tryLock();
                    if (acquired) {
                        try {
                            testService.concurrentDistributedLock();
                            successCount.incrementAndGet();
                        } finally {
                            lock.unlock();
                        }
                    }
                });
                futures.add(future);
            }

            // Ждем завершения всех потоков
            for (var future : futures) {
                future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            }

            // Then
            assertThat(successCount.get())
                    .as("Только один поток должен выполниться успешно")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("Должен корректно обрабатывать разные lockName")
        void shouldHandleDifferentLockNames() throws Exception {
            // Given
            String lockName1 = "first-distributed-lock";
            String lockName2 = "second-distributed-lock";
            AtomicInteger executionCount1 = new AtomicInteger(0);
            AtomicInteger executionCount2 = new AtomicInteger(0);

            // When - выполняем методы с разными lockName параллельно
            var future1 = executorService.submit(() -> {
                DistributedLock lock = lockRegistry.obtain(lockName1);
                boolean acquired = lock.tryLock();
                if (acquired) {
                    try {
                        testService.firstDistributedLock();
                        executionCount1.incrementAndGet();
                    } finally {
                        lock.unlock();
                    }
                }
            });

            var future2 = executorService.submit(() -> {
                DistributedLock lock = lockRegistry.obtain(lockName2);
                boolean acquired = lock.tryLock();
                if (acquired) {
                    try {
                        testService.secondDistributedLock();
                        executionCount2.incrementAndGet();
                    } finally {
                        lock.unlock();
                    }
                }
            });

            // Ждем завершения обоих потоков
            future1.get(10, java.util.concurrent.TimeUnit.SECONDS);
            future2.get(10, java.util.concurrent.TimeUnit.SECONDS);

            // Then
            assertThat(executionCount1.get())
                    .as("Первый метод должен выполниться под своей блокировкой")
                    .isEqualTo(1);
            assertThat(executionCount2.get())
                    .as("Второй метод должен выполниться под своей блокировкой")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Hook события")
    class HookEventsTests {

        @Test
        @DisplayName("Должен вызвать @OnLockAcquired при захвате блокировки")
        void shouldCallOnLockAcquiredWhenAcquiringLock() {
            // Given
            String lockName = "hook-distributed-lock";

            // When
            DistributedLock lock = lockRegistry.obtain(lockName);
            boolean acquired = lock.tryLock();
            assertThat(acquired).as("Блокировка должна захватываться").isTrue();

            try {
                testService.hookDistributedLock();
                testService.onLockAcquired();
            } finally {
                lock.unlock();
            }

            // Then
            assertThat(testService.lockAcquiredCalled.get())
                    .as("@OnLockAcquired должен быть вызван")
                    .isTrue();
        }

        @Test
        @DisplayName("Должен вызвать @OnLockReleased при освобождении блокировки")
        void shouldCallOnLockReleasedWhenReleasingLock() {
            // Given
            String lockName = "hook-distributed-lock";

            // When
            DistributedLock lock = lockRegistry.obtain(lockName);
            boolean acquired = lock.tryLock();
            assertThat(acquired).as("Блокировка должна захватываться").isTrue();

            try {
                testService.hookDistributedLock();
            } finally {
                testService.onLockReleased();
                lock.unlock();
            }

            // Then
            assertThat(testService.lockReleasedCalled.get())
                    .as("@OnLockReleased должен быть вызван")
                    .isTrue();
        }

        @Test
        @DisplayName("Должен вызвать оба hook события в правильном порядке")
        void shouldCallBothHookEventsInCorrectOrder() {
            // Given
            String lockName = "hook-distributed-lock";

            // When
            DistributedLock lock = lockRegistry.obtain(lockName);
            boolean acquired = lock.tryLock();
            assertThat(acquired).as("Блокировка должна захватываться").isTrue();

            try {
                testService.onLockAcquired();
                testService.hookDistributedLock();
            } finally {
                testService.onLockReleased();
                lock.unlock();
            }

            // Then
            assertThat(testService.lockAcquiredCalled.get())
                    .as("@OnLockAcquired должен быть вызван")
                    .isTrue();
            assertThat(testService.lockReleasedCalled.get())
                    .as("@OnLockReleased должен быть вызван")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Keep-Alive функциональность")
    class KeepAliveTests {

        @Test
        @DisplayName("Должен продлить блокировку с @DistributedLock keepAliveLockUntil")
        void shouldExtendLockWithKeepAliveLockUntil() {
            // Given
            String lockName = "keep-alive-distributed-lock";

            // When
            DistributedLock lock = lockRegistry.obtain(lockName);
            boolean acquired = lock.tryLock();
            assertThat(acquired).as("Блокировка должна захватываться").isTrue();

            try {
                testService.keepAliveDistributedLock();
            } finally {
                lock.unlock();
            }

            // Then
            assertThat(testService.executed.get())
                    .as("Метод с keep-alive должен выполниться")
                    .isTrue();
        }
    }

    static class TestService {

        private final AtomicBoolean executed = new AtomicBoolean(false);
        private final AtomicBoolean lockAcquiredCalled = new AtomicBoolean(false);
        private final AtomicBoolean lockReleasedCalled = new AtomicBoolean(false);

        public void basicDistributedLock() {
            executed.set(true);
        }

        public void failingDistributedLock() {
            throw new RuntimeException("Test exception");
        }

        public void concurrentDistributedLock() {
            executed.set(true);
            try {
                Thread.sleep(100); // Имитируем работу
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        public void firstDistributedLock() {
            executed.set(true);
            try {
                Thread.sleep(50); // Имитируем работу
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        public void secondDistributedLock() {
            executed.set(true);
            try {
                Thread.sleep(50); // Имитируем работу
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        public void hookDistributedLock() {
            executed.set(true);
        }

        public void onLockAcquired() {
            lockAcquiredCalled.set(true);
        }

        public void onLockReleased() {
            lockReleasedCalled.set(true);
        }

        public void keepAliveDistributedLock() {
            executed.set(true);
            try {
                Thread.sleep(200); // Имитируем длительную работу
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void verifyRedisIsHealthy() {
        assertThat(redisServer.isRedisHealthy(redisTemplate))
                .as("Redis должен быть доступен и работать корректно")
                .isTrue();
    }

    private DistributedLockRegistry createDistributedLockRegistry() {
        return new RedisDistributedLockRegistry(
                TEST_KEY_PREFIX,
                DEFAULT_TTL,
                IDLE_BETWEEN_TRIES,
                redisTemplate,
                new NoOpLeaderElectionMetrics(),
                false,
                CircuitBreakerFactory.create(5, Duration.ofSeconds(30))
        );
    }

}