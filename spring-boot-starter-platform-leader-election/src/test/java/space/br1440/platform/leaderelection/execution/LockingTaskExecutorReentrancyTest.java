package space.br1440.platform.leaderelection.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import space.br1440.platform.leaderelection.execution.keepalive.KeepAliveLockFactory;
import space.br1440.platform.leaderelection.lock.DistributedLockRegistry;
import space.br1440.platform.leaderelection.lock.persistence.redis.RedisDistributedLock;
import space.br1440.platform.leaderelection.lock.persistence.redis.RedisLeaseRecordRepository;
import space.br1440.platform.leaderelection.lock.persistence.redis.resilience.CircuitBreakerFactory;
import space.br1440.platform.leaderelection.metrics.NoOpLeaderElectionMetrics;
import space.br1440.platform.leaderelection.processor.LockConfigurationValidationProcessor;
import space.br1440.platform.leaderelection.processor.LockHookProcessor;
import space.br1440.platform.leaderelection.schedulerlocking.configuration.LockConfiguration;
import space.br1440.platform.leaderelection.schedulerlocking.configuration.LockConfigurationExtractor;
import space.br1440.platform.leaderelection.testutil.extension.annotation.WithLeaderElectionMocks;
import space.br1440.platform.leaderelection.testutil.extension.annotation.WithEmbeddedRedis;
import space.br1440.platform.leaderelection.testutil.support.RedisTestSupport;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@WithEmbeddedRedis
@WithLeaderElectionMocks
@DisplayName("LockingTaskExecutor Reentrancy Tests")
class LockingTaskExecutorReentrancyTest {

    private RedisTestSupport support;
    private RedisLeaseRecordRepository repository;
    private ScheduledExecutorService scheduler;
    private LockingTaskExecutor executor;
    private DistributedLockRegistry lockRegistry;
    private LockConfigurationExtractor configExtractor;

    private static final String LOCK_ROLE_1 = "test-lock-1";
    private static final String LOCK_ROLE_2 = "test-lock-2";
    private static final String HOLDER_IDENTITY = "test-holder";
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(1);
    private static final Duration IDLE_BETWEEN_TRIES = Duration.ofMillis(50);

    @BeforeEach
    void setUp(RedisConnectionFactory redisConnectionFactory) {
        support = new RedisTestSupport(redisConnectionFactory);
        repository = new RedisLeaseRecordRepository(
                support.getRedisTemplate(),
                new ObjectMapper(),
                false,
                CircuitBreakerFactory.create(5, Duration.ofSeconds(30))
        );
        scheduler = Executors.newScheduledThreadPool(2);

        RedisDistributedLock lock1 = createRedisLock(LOCK_ROLE_1);
        RedisDistributedLock lock2 = createRedisLock(LOCK_ROLE_2);

        lockRegistry = mock(DistributedLockRegistry.class);
        when(lockRegistry.obtain(LOCK_ROLE_1)).thenReturn(lock1);
        when(lockRegistry.obtain(LOCK_ROLE_2)).thenReturn(lock2);

        configExtractor = mock(LockConfigurationExtractor.class);

        LockHookProcessor hookProcessor = mock(LockHookProcessor.class);

        KeepAliveLockFactory keepAliveLockFactory = new KeepAliveLockFactory(scheduler);

        LockConfigurationValidationProcessor validationProcessor = mock(LockConfigurationValidationProcessor.class);
        when(validationProcessor.getValidatedConfiguration(any())).thenReturn(null);

        executor = new LockingTaskExecutor(
                configExtractor,
                hookProcessor,
                keepAliveLockFactory,
                validationProcessor
        );
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            try {
                //noinspection ResultOfMethodCallIgnored
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        support.flushAll();
    }

    private RedisDistributedLock createRedisLock(String lockKey) {
        return new RedisDistributedLock(
                lockKey,
                HOLDER_IDENTITY,
                DEFAULT_TTL,
                IDLE_BETWEEN_TRIES,
                repository,
                new NoOpLeaderElectionMetrics(),
                CircuitBreakerFactory.create(5, Duration.ofSeconds(30))
        );
    }

    private void configureLockConfig(String leaseRole, Duration lockAtMostFor, Duration lockAtLeastFor) {
        LockConfiguration config = new LockConfiguration(
                leaseRole,
                lockAtMostFor,
                lockAtLeastFor,
                null
        );
        when(configExtractor.extractLockConfiguration(any())).thenReturn(config);
    }

    @Nested
    @DisplayName("Single Lock Reentrancy Tests")
    class SingleLockReentrancyTests {

        @Test
        @DisplayName("Lock should be reentrant - nested acquisition of same lock succeeds")
        void lockShouldBeReentrant() throws NoSuchMethodException {
            // Given
            Method method = getClass().getDeclaredMethod("lockShouldBeReentrant");
            configureLockConfig(LOCK_ROLE_1, Duration.ofSeconds(10), null);

            AtomicInteger executionCount = new AtomicInteger(0);
            AtomicInteger nestedExecutionCount = new AtomicInteger(0);

            // When: Выполняем задачу, которая снова захватывает тот же Lock (реентерабельный)
            executor.executeWithLock(() -> {
                executionCount.incrementAndGet();

                // Вложенный захват Lock - должно успешно выполниться благодаря реентерабельности
                executor.executeWithLock(nestedExecutionCount::incrementAndGet, this, method, lockRegistry);
            }, this, method, lockRegistry);

            // Then: Должны выполниться как внешняя, так и вложенная задачи
            assertThat(executionCount.get())
                    .as("Внешняя задача должна выполниться один раз")
                    .isEqualTo(1);
            assertThat(nestedExecutionCount.get())
                    .as("Вложенная задача должна выполниться один раз благодаря реентерабельности")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("Lock should support multiple reentrant levels")
        void lockShouldSupportMultipleReentrantLevels() throws NoSuchMethodException {
            // Given
            Method method = getClass().getDeclaredMethod("lockShouldSupportMultipleReentrantLevels");
            configureLockConfig(LOCK_ROLE_1, Duration.ofSeconds(10), null);

            AtomicInteger level1Count = new AtomicInteger(0);
            AtomicInteger level2Count = new AtomicInteger(0);
            AtomicInteger level3Count = new AtomicInteger(0);

            // When: Выполняем вложенные Lock на 3 уровня в глубину
            executor.executeWithLock(() -> {
                level1Count.incrementAndGet();

                executor.executeWithLock(() -> {
                    level2Count.incrementAndGet();
                    executor.executeWithLock(level3Count::incrementAndGet, this, method, lockRegistry);
                }, this, method, lockRegistry);
            }, this, method, lockRegistry);

            // Then: Все три уровня должны выполниться
            assertThat(level1Count.get()).isEqualTo(1);
            assertThat(level2Count.get()).isEqualTo(1);
            assertThat(level3Count.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("Lock should be released after all reentrant calls complete")
        void lockShouldBeReleasedAfterAllReentrantCallsComplete() throws NoSuchMethodException {
            // Given
            Method method = getClass().getDeclaredMethod("lockShouldBeReleasedAfterAllReentrantCallsComplete");
            configureLockConfig(LOCK_ROLE_1, Duration.ofSeconds(10), null);

            Lock testLock = lockRegistry.obtain(LOCK_ROLE_1);

            // When: Выполняем вложенные Lock
            executor.executeWithLock(() -> {
                executor.executeWithLock(() -> {
                    // Внутри вложенного Lock
                }, this, method, lockRegistry);
                // После вложенного разблокирования
            }, this, method, lockRegistry);

            // Then: Lock должен быть полностью освобожден и доступен для нового захвата
            boolean canAcquire = testLock.tryLock();
            assertThat(canAcquire)
                    .as("Lock должен быть доступен после завершения всех реентерабельных вызовов")
                    .isTrue();
            testLock.unlock();
        }
    }

    @Nested
    @DisplayName("Multiple Locks Reentrancy Tests")
    class MultipleLockReentrancyTests {

        @Test
        @DisplayName("Lock should be reentrant for multiple different locks")
        void lockShouldBeReentrantForMultipleDifferentLocks() throws NoSuchMethodException {
            // Given: Две разные конфигурации Lock
            Method method = getClass().getDeclaredMethod("lockShouldBeReentrantForMultipleDifferentLocks");

            AtomicInteger lock1ExecutionCount = new AtomicInteger(0);
            AtomicInteger lock2ExecutionCount = new AtomicInteger(0);

            // When: Выполняем с первым Lock, затем захватываем второй Lock внутри
            configureLockConfig(LOCK_ROLE_1, Duration.ofSeconds(10), null);
            executor.executeWithLock(() -> {
                lock1ExecutionCount.incrementAndGet();

                // Переключаемся на второй Lock
                configureLockConfig(LOCK_ROLE_2, Duration.ofSeconds(10), null);
                executor.executeWithLock(lock2ExecutionCount::incrementAndGet, this, method, lockRegistry);
            }, this, method, lockRegistry);

            // Then: Обе задачи должны выполниться без дедлока
            assertThat(lock1ExecutionCount.get())
                    .as("Первая задача с Lock должна выполниться")
                    .isEqualTo(1);
            assertThat(lock2ExecutionCount.get())
                    .as("Вторая задача с Lock должна выполниться")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("Multiple locks should not interfere with each other's reentrancy")
        void multipleLocksShouldNotInterfereWithEachOther() throws NoSuchMethodException {
            // Given: Два разных Lock
            Method method = getClass().getDeclaredMethod("multipleLocksShouldNotInterfereWithEachOther");

            AtomicInteger interleaved = new AtomicInteger(0);

            // When: Чередуем захваты Lock: Lock1 -> Lock2 -> Lock1 (реентерабельный) -> Lock2 (реентерабельный)
            configureLockConfig(LOCK_ROLE_1, Duration.ofSeconds(10), null);
            executor.executeWithLock(() -> {
                interleaved.addAndGet(1); // 1

                configureLockConfig(LOCK_ROLE_2, Duration.ofSeconds(10), null);
                executor.executeWithLock(() -> {
                    interleaved.addAndGet(10); // 11

                    // Повторный вход в Lock1
                    configureLockConfig(LOCK_ROLE_1, Duration.ofSeconds(10), null);
                    executor.executeWithLock(() -> {
                        interleaved.addAndGet(100); // 111

                        // Повторный вход в Lock2
                        configureLockConfig(LOCK_ROLE_2, Duration.ofSeconds(10), null);
                        executor.executeWithLock(() -> {
                            interleaved.addAndGet(1000); // 1111
                        }, this, method, lockRegistry);

                    }, this, method, lockRegistry);

                }, this, method, lockRegistry);

            }, this, method, lockRegistry);

            // Then: Все вложенные выполнения должны завершиться успешно
            assertThat(interleaved.get())
                    .as("Все четыре захвата Lock должны выполниться")
                    .isEqualTo(1111);
        }
    }

    @Nested
    @DisplayName("Result Return Tests")
    class ResultReturnTests {

        @Test
        @DisplayName("Should execute with result from locked context")
        void shouldExecuteWithResult() throws NoSuchMethodException {
            // Given: Конфигурация Lock
            Method method = getClass().getDeclaredMethod("shouldExecuteWithResult");
            configureLockConfig(LOCK_ROLE_1, Duration.ofSeconds(10), null);

            AtomicInteger result = new AtomicInteger(0);

            // When: Выполняем задачу, которая создает результат
            executor.executeWithLock(() -> result.set(42), this, method, lockRegistry);

            // Then: Результат должен быть доступен после выполнения
            assertThat(result.get())
                    .as("Результат должен быть установлен в заблокированном контексте")
                    .isEqualTo(42);
        }

        @Test
        @DisplayName("Should return result from nested reentrant lock")
        void shouldReturnResultFromNestedReentrantLock() throws NoSuchMethodException {
            // Given
            Method method = getClass().getDeclaredMethod("shouldReturnResultFromNestedReentrantLock");
            configureLockConfig(LOCK_ROLE_1, Duration.ofSeconds(10), null);

            AtomicInteger outerResult = new AtomicInteger(0);
            AtomicInteger nestedResult = new AtomicInteger(0);

            // When: Выполняем вложенные задачи, которые создают результаты
            executor.executeWithLock(() -> {
                outerResult.set(10);
                executor.executeWithLock(() -> nestedResult.set(20), this, method, lockRegistry);
                outerResult.addAndGet(nestedResult.get());
            }, this, method, lockRegistry);

            // Then: Оба результата должны быть корректными
            assertThat(outerResult.get())
                    .as("Внешний результат должен включать вложенный результат")
                    .isEqualTo(30);
            assertThat(nestedResult.get())
                    .as("Вложенный результат должен быть установлен")
                    .isEqualTo(20);
        }

        @Test
        @DisplayName("Should accumulate results across multiple reentrant levels")
        void shouldAccumulateResultsAcrossMultipleReentrantLevels() throws NoSuchMethodException {
            // Given
            Method method = getClass().getDeclaredMethod("shouldAccumulateResultsAcrossMultipleReentrantLevels");
            configureLockConfig(LOCK_ROLE_1, Duration.ofSeconds(10), null);

            AtomicInteger accumulator = new AtomicInteger(0);

            // When: Выполняем 3-уровневые вложенные Lock с накоплением
            executor.executeWithLock(() -> {
                accumulator.addAndGet(1); // Level 1: 1

                executor.executeWithLock(() -> {
                    accumulator.addAndGet(10); // Level 2: 11

                    executor.executeWithLock(() -> {
                        accumulator.addAndGet(100); // Level 3: 111
                    }, this, method, lockRegistry);

                }, this, method, lockRegistry);

            }, this, method, lockRegistry);

            // Then: Накопленный результат должен отражать все уровни
            assertThat(accumulator.get())
                    .as("Результат должен накапливаться по всем реентерабельным уровням")
                    .isEqualTo(111);
        }
    }

    @Nested
    @DisplayName("Deadlock Prevention Tests")
    class DeadlockPreventionTests {

        @Test
        @DisplayName("Should prevent deadlock with circular lock dependencies")
        void shouldPreventDeadlockWithCircularDependencies() throws NoSuchMethodException {
            // Given
            Method method = getClass().getDeclaredMethod("shouldPreventDeadlockWithCircularDependencies");

            AtomicInteger completionCount = new AtomicInteger(0);

            // When: Пытаемся захватить Lock в разном порядке (потенциальный сценарий дедлока)
            // Порядок 1: Lock1 -> Lock2
            configureLockConfig(LOCK_ROLE_1, Duration.ofSeconds(10), null);
            executor.executeWithLock(() -> {
                configureLockConfig(LOCK_ROLE_2, Duration.ofSeconds(10), null);
                executor.executeWithLock(completionCount::incrementAndGet, this, method, lockRegistry);
            }, this, method, lockRegistry);

            // Порядок 2: Lock2 -> Lock1 (обратный порядок)
            configureLockConfig(LOCK_ROLE_2, Duration.ofSeconds(10), null);
            executor.executeWithLock(() -> {
                configureLockConfig(LOCK_ROLE_1, Duration.ofSeconds(10), null);
                executor.executeWithLock(completionCount::incrementAndGet, this, method, lockRegistry);
            }, this, method, lockRegistry);

            // Then: Обе последовательности должны завершиться без дедлока
            assertThat(completionCount.get())
                    .as("Обе последовательности Lock должны успешно завершиться")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("Reentrant locks should not cause self-deadlock")
        void reentrantLocksShouldNotCauseSelfDeadlock() throws NoSuchMethodException {
            // Given: Конфигурация Lock
            Method method = getClass().getDeclaredMethod("reentrantLocksShouldNotCauseSelfDeadlock");
            configureLockConfig(LOCK_ROLE_1, Duration.ofSeconds(10), null);

            AtomicInteger deepLevel = new AtomicInteger(0);
            final int MAX_DEPTH = 10;

            // When: Захватываем один и тот же Lock рекурсивно много раз
            Runnable recursiveTask = new Runnable() {
                @Override
                public void run() {
                    if (deepLevel.get() < MAX_DEPTH) {
                        deepLevel.incrementAndGet();
                        executor.executeWithLock(this,
                                LockingTaskExecutorReentrancyTest.this,
                                method,
                                lockRegistry);
                    }
                }
            };

            executor.executeWithLock(recursiveTask, this, method, lockRegistry);

            // Then: Должны достичь максимальной глубины без дедлока
            assertThat(deepLevel.get())
                    .as("Должны завершиться все рекурсивные уровни без дедлока")
                    .isEqualTo(MAX_DEPTH);
        }
    }

}
