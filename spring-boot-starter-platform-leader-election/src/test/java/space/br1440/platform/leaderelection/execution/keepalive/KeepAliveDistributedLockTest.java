package space.br1440.platform.leaderelection.execution.keepalive;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import space.br1440.platform.leaderelection.lock.persistence.model.LeaseRecord;
import space.br1440.platform.leaderelection.lock.persistence.redis.RedisDistributedLock;
import space.br1440.platform.leaderelection.lock.persistence.redis.RedisLeaseRecordRepository;
import space.br1440.platform.leaderelection.lock.persistence.redis.resilience.CircuitBreakerFactory;
import space.br1440.platform.leaderelection.metrics.NoOpLeaderElectionMetrics;
import space.br1440.platform.leaderelection.testutil.extension.annotation.WithEmbeddedRedis;
import space.br1440.platform.leaderelection.testutil.support.RedisTestSupport;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@WithEmbeddedRedis
@space.br1440.platform.leaderelection.testutil.extension.annotation.WithLeaderElectionMocks
@DisplayName("KeepAliveLock Integration Tests")
class KeepAliveDistributedLockTest {

    private RedisTestSupport support;
    private RedisLeaseRecordRepository repository;
    private ScheduledExecutorService scheduler;

    private static final String TEST_LOCK_KEY = "test-keep-alive-lock";
    private static final String HOLDER_IDENTITY = "test-holder";
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

    private KeepAliveLock createKeepAliveLock(String lockKey, Duration lockAtMostFor, Duration keepAliveLockUntil) {
        RedisDistributedLock redisLock = new RedisDistributedLock(
                lockKey,
                HOLDER_IDENTITY,
                lockAtMostFor,
                IDLE_BETWEEN_TRIES,
                repository,
                new NoOpLeaderElectionMetrics(),
                CircuitBreakerFactory.create(5, Duration.ofSeconds(30))
        );
        return new KeepAliveLock(redisLock, lockKey, lockAtMostFor, keepAliveLockUntil, scheduler);
    }

    private void assertLockExists(String lockKey) {
        LeaseRecord record = repository.getCurrentRecord(lockKey);
        assertThat(record)
                .as("Lock '%s' should exist in Redis", lockKey)
                .isNotNull();
        assertThat(record.getHolderIdentity())
                .as("Lock holder should be '%s'", HOLDER_IDENTITY)
                .isEqualTo(HOLDER_IDENTITY);
    }

    private void assertLockNotExists(String lockKey) {
        LeaseRecord record = repository.getCurrentRecord(lockKey);
        assertThat(record)
                .as("Lock '%s' should not exist in Redis", lockKey)
                .isNull();
    }

    @Nested
    @DisplayName("Lock Extension Tests")
    class LockExtensionTests {

        @Test
        @DisplayName("Должен автоматически продлевать lock до истечения TTL")
        void shouldBeAbleToExtendLock() throws InterruptedException {
            // Given: Lock с коротким TTL (2 секунды) и keep-alive на 5 секунд
            Duration shortTtl = Duration.ofSeconds(2);
            Duration keepAlive = Duration.ofSeconds(5);
            KeepAliveLock lock = createKeepAliveLock(TEST_LOCK_KEY, shortTtl, keepAlive);

            // When: Получаем lock и запускаем keep-alive
            boolean acquired = lock.tryLock();
            assertThat(acquired).isTrue();
            assertLockExists(TEST_LOCK_KEY);

            Instant acquireTime = repository.getCurrentRecord(TEST_LOCK_KEY).getAcquireTime();
            lock.startExtension();

            // Then: Ждем больше оригинального TTL
            Thread.sleep(3000);

            // Lock должен все еще существовать благодаря продлению
            assertLockExists(TEST_LOCK_KEY);

            LeaseRecord afterExtension = repository.getCurrentRecord(TEST_LOCK_KEY);
            assertThat(afterExtension.getRenewTime())
                    .as("renewTime должен быть обновлен после продления")
                    .isAfter(acquireTime);

            lock.stopExtension();
            lock.unlock();
        }

        @Test
        @DisplayName("Должен выполнять множественное продление lock")
        void shouldBeAbleToExtendMultipleTimes() throws InterruptedException {
            // Given: Lock с очень коротким TTL (1 секунда) и keep-alive на 6 секунд
            Duration veryShortTtl = Duration.ofSeconds(1);
            Duration longKeepAlive = Duration.ofSeconds(6);
            KeepAliveLock lock = createKeepAliveLock(TEST_LOCK_KEY, veryShortTtl, longKeepAlive);

            // When: Получаем lock и запускаем keep-alive
            boolean acquired = lock.tryLock();
            assertThat(acquired).isTrue();

            Instant initialRenewTime = repository.getCurrentRecord(TEST_LOCK_KEY).getRenewTime();
            lock.startExtension();

            // Then: Ждем достаточно времени для нескольких продлений (4 секунды = ~4 продления)
            Thread.sleep(4000);

            // Lock должен все еще существовать
            assertLockExists(TEST_LOCK_KEY);

            LeaseRecord afterMultipleExtensions = repository.getCurrentRecord(TEST_LOCK_KEY);
            assertThat(afterMultipleExtensions.getRenewTime())
                    .as("renewTime должен быть значительно позже начального времени после множественных продлений")
                    .isAfter(initialRenewTime.plusSeconds(3));

            lock.stopExtension();
            lock.unlock();
        }
    }

    @Nested
    @DisplayName("Lock Extension Failure Tests")
    class LockExtensionFailureTests {

        @Test
        @DisplayName("Не должен продлевать освобожденный lock")
        void shouldNotBeAbleToExtendUnlockedLock() throws InterruptedException {
            // Given: Lock с keep-alive
            Duration ttl = Duration.ofSeconds(5);
            Duration keepAlive = Duration.ofSeconds(10);
            KeepAliveLock lock = createKeepAliveLock(TEST_LOCK_KEY, ttl, keepAlive);

            // When: Получаем lock, запускаем keep-alive, затем освобождаем
            boolean acquired = lock.tryLock();
            assertThat(acquired).isTrue();
            lock.startExtension();

            Thread.sleep(500); // Даем время для первого продления

            lock.unlock(); // Освобождаем lock (это также останавливает extension)
            assertLockNotExists(TEST_LOCK_KEY);

            // Then: Lock не должен появиться снова после unlock
            Thread.sleep(3000);
            assertLockNotExists(TEST_LOCK_KEY);
        }

        @Test
        @DisplayName("Не должен продлевать истекший lock")
        void shouldNotBeAbleToExtendExpiredLock() throws InterruptedException {
            // Given: Lock с очень коротким TTL (500ms) и keep-alive
            Duration veryShortTtl = Duration.ofMillis(500);
            Duration keepAlive = Duration.ofSeconds(5);

            // Создаем lock напрямую в Redis с истекшим временем
            LeaseRecord expiredRecord = new LeaseRecord(
                    HOLDER_IDENTITY,
                    Instant.now().minusSeconds(10),
                    Instant.now().minusSeconds(10),
                    1, // TTL 1 секунда (уже истек)
                    0,
                    1
            );
            repository.createRecord(TEST_LOCK_KEY, expiredRecord, Duration.ofSeconds(1));

            // When: Пытаемся создать KeepAliveLock на истекшем lock
            KeepAliveLock lock = createKeepAliveLock(TEST_LOCK_KEY, veryShortTtl, keepAlive);

            // Ждем истечения TTL в Redis
            Thread.sleep(1500);

            // Then: Lock должен исчезнуть (TTL истек)
            assertLockNotExists(TEST_LOCK_KEY);

            // Даже если запустить extension, lock не должен появиться
            // (т.к. он уже не существует в Redis)
            lock.startExtension();
            Thread.sleep(1000);
            assertLockNotExists(TEST_LOCK_KEY);

            // Cleanup
            lock.stopExtension();
        }
    }

    @Nested
    @DisplayName("Lock Extension with LockAtLeastFor Tests")
    class LockAtLeastForTests {

        @Test
        @DisplayName("Должен продлевать lock с учетом lockAtLeastFor")
        void shouldBeAbleToExtendAtLeast() throws InterruptedException {
            // Given: Lock с TTL и keep-alive
            Duration ttl = Duration.ofSeconds(3);
            Duration keepAlive = Duration.ofSeconds(6);
            KeepAliveLock lock = createKeepAliveLock(TEST_LOCK_KEY, ttl, keepAlive);

            // When: Получаем lock и запускаем keep-alive
            boolean acquired = lock.tryLock();
            assertThat(acquired).isTrue();
            lock.startExtension();

            Instant beforeExtension = Instant.now();

            // Ждем первого продления (TTL/2 = 1.5 секунды)
            Thread.sleep(2000);

            // Then: Lock должен быть продлен
            assertLockExists(TEST_LOCK_KEY);

            LeaseRecord afterExtension = repository.getCurrentRecord(TEST_LOCK_KEY);
            assertThat(afterExtension.getRenewTime())
                    .as("Lock должен быть продлен")
                    .isAfter(beforeExtension);

            // Останавливаем extension и освобождаем lock
            lock.stopExtension();

            // Lock должен все еще существовать некоторое время (благодаря TTL)
            assertLockExists(TEST_LOCK_KEY);

            lock.unlock();
        }
    }

    @Nested
    @DisplayName("Lock Extension State Management Tests")
    class LockExtensionStateTests {

        @Test
        @DisplayName("Lock не может быть продлен дважды одновременно")
        void lockCanNotBeExtendedTwice() {
            // Given: Lock с keep-alive
            Duration ttl = Duration.ofSeconds(5);
            Duration keepAlive = Duration.ofSeconds(10);
            KeepAliveLock lock = createKeepAliveLock(TEST_LOCK_KEY, ttl, keepAlive);

            // When: Получаем lock и запускаем keep-alive
            boolean acquired = lock.tryLock();
            assertThat(acquired).isTrue();
            lock.startExtension();

            // Then: Попытка повторного запуска extension должна бросить исключение
            assertThatThrownBy(lock::startExtension)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Extension already started");

            lock.stopExtension();
            lock.unlock();
        }

        @Test
        @DisplayName("Lock может быть остановлен и перезапущен после остановки")
        void lockCanBeRestartedAfterStop() throws InterruptedException {
            // Given: Lock с keep-alive
            Duration ttl = Duration.ofSeconds(3);
            Duration keepAlive = Duration.ofSeconds(10);
            KeepAliveLock lock = createKeepAliveLock(TEST_LOCK_KEY, ttl, keepAlive);

            // When: Получаем lock, запускаем и останавливаем keep-alive
            boolean acquired = lock.tryLock();
            assertThat(acquired).isTrue();
            lock.startExtension();
            Thread.sleep(500);

            lock.stopExtension();
            Thread.sleep(500);

            // Then: Должны суметь перезапустить extension
            lock.startExtension();
            Thread.sleep(2000);

            // Lock должен все еще существовать
            assertLockExists(TEST_LOCK_KEY);

            lock.stopExtension();
            lock.unlock();
        }

        @Test
        @DisplayName("Unlock должен останавливать extension автоматически")
        void lockCanNotBeUnlockedAfterExtending() throws InterruptedException {
            // Given: Lock с активным keep-alive
            Duration ttl = Duration.ofSeconds(3);
            Duration keepAlive = Duration.ofSeconds(10);
            KeepAliveLock lock = createKeepAliveLock(TEST_LOCK_KEY, ttl, keepAlive);

            boolean acquired = lock.tryLock();
            assertThat(acquired).isTrue();
            lock.startExtension();

            Thread.sleep(1000); // Даем время для продления

            // When: Вызываем unlock
            lock.unlock();

            // Then: Lock должен быть освобожден
            assertLockNotExists(TEST_LOCK_KEY);

            // Extension должен быть остановлен, lock не должен появиться снова
            Thread.sleep(2000);
            assertLockNotExists(TEST_LOCK_KEY);
        }
    }

    @Nested
    @DisplayName("Lock Extension Timeout Tests")
    class LockExtensionTimeoutTests {

        @Test
        @DisplayName("Extension должен останавливаться после достижения keepAliveLockUntil")
        void shouldStopExtensionAfterKeepAliveTimeout() throws InterruptedException {
            // Given: Lock с коротким keep-alive (2 секунды)
            Duration ttl = Duration.ofSeconds(3);
            Duration shortKeepAlive = Duration.ofSeconds(2);
            KeepAliveLock lock = createKeepAliveLock(TEST_LOCK_KEY, ttl, shortKeepAlive);

            // When: Получаем lock и запускаем keep-alive
            boolean acquired = lock.tryLock();
            assertThat(acquired).isTrue();
            lock.startExtension();

            // Ждем первого продления
            Thread.sleep(2000);
            assertLockExists(TEST_LOCK_KEY);

            // Then: Ждем истечения keepAliveLockUntil и TTL
            Thread.sleep(2000);

            // Extension должен остановиться, и lock должен истечь
            await().atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> assertLockNotExists(TEST_LOCK_KEY));

            lock.stopExtension();
        }

        @Test
        @DisplayName("Multiple stop calls should be safe")
        void multipleStopCallsShouldBeSafe() {
            // Given: Lock с keep-alive
            Duration ttl = Duration.ofSeconds(5);
            Duration keepAlive = Duration.ofSeconds(10);
            KeepAliveLock lock = createKeepAliveLock(TEST_LOCK_KEY, ttl, keepAlive);

            boolean acquired = lock.tryLock();
            assertThat(acquired).isTrue();
            lock.startExtension();

            // When & Then: Множественные вызовы stopExtension не должны вызывать ошибок
            lock.stopExtension();
            lock.stopExtension();
            lock.stopExtension();

            lock.unlock();
        }
    }

}
