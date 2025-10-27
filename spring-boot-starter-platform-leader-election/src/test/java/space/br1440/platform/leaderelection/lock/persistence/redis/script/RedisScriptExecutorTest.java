package space.br1440.platform.leaderelection.lock.persistence.redis.script;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import space.br1440.platform.leaderelection.lock.persistence.model.LeaseOperationResult;
import space.br1440.platform.leaderelection.testutil.extension.annotation.WithEmbeddedRedis;
import space.br1440.platform.leaderelection.testutil.support.RedisTestSupport;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WithEmbeddedRedis
@space.br1440.platform.leaderelection.testutil.extension.annotation.WithLeaderElectionMocks
@DisplayName("Тесты RedisScriptExecutor")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisScriptExecutorTest {

    private static final String TEST_LEASE_KEY = "test:lease:script-executor";
    private static final String IDENTITY_1 = "instance-1";
    private static final String IDENTITY_2 = "instance-2";
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(30);
    private static final int DEFAULT_LEASE_DURATION = 30;

    private RedisScriptExecutor scriptExecutor;
    private RedisTestSupport testSupport;

    @BeforeEach
    void setUp(RedisConnectionFactory connectionFactory, RedisTestSupport support) {
        this.testSupport = support;
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        this.scriptExecutor = new RedisScriptExecutor(redisTemplate, new ObjectMapper());

        support.delete(TEST_LEASE_KEY);
    }

    @AfterEach
    void tearDown() {
        testSupport.delete(TEST_LEASE_KEY);
    }

    @Nested
    @DisplayName("Операции создания Lease")
    @Order(1)
    class CreateLeaseTests {

        @Test
        @DisplayName("Должен успешно создать lease когда его не существует")
        void shouldCreateLeaseWhenNotExists() {
            // Given: Нет существующего lease
            assertThat(testSupport.exists(TEST_LEASE_KEY))
                    .as("Lease не должен существовать до создания")
                    .isFalse();

            // When: Создаём lease
            LeaseOperationResult result = scriptExecutor.createLease(
                    TEST_LEASE_KEY,
                    IDENTITY_1,
                    DEFAULT_TTL,
                    DEFAULT_LEASE_DURATION
            );

            // Then: Операция успешна
            assertThat(result)
                    .as("Результат должен быть Success")
                    .isInstanceOf(LeaseOperationResult.Success.class);

            LeaseOperationResult.Success success = (LeaseOperationResult.Success) result;
            assertThat(success.operationType()).isEqualTo("CREATE");
            assertThat(success.leaseKey()).isEqualTo(TEST_LEASE_KEY);
            assertThat(success.data()).isNotNull();
            assertThat(success.data().version()).isEqualTo(1L);

            // And: Lease существует в Redis
            assertThat(testSupport.exists(TEST_LEASE_KEY))
                    .as("Lease должен существовать после создания")
                    .isTrue();

            // And: TTL установлен корректно
            long ttl = testSupport.getTtl(TEST_LEASE_KEY);
            assertThat(ttl)
                    .as("TTL должен быть приблизительно равен заданному")
                    .isBetween(28L, 30L);
        }

        @Test
        @DisplayName("Должен вернуть Conflict когда lease уже существует")
        void shouldReturnConflictWhenLeaseExists() {
            // Given: Существующий lease от IDENTITY_1
            scriptExecutor.createLease(TEST_LEASE_KEY, IDENTITY_1, DEFAULT_TTL, DEFAULT_LEASE_DURATION);

            // When: IDENTITY_2 пытается создать lease
            LeaseOperationResult result = scriptExecutor.createLease(
                    TEST_LEASE_KEY,
                    IDENTITY_2,
                    DEFAULT_TTL,
                    DEFAULT_LEASE_DURATION
            );

            // Then: Должен вернуть Conflict
            assertThat(result)
                    .as("Результат должен быть Conflict")
                    .isInstanceOf(LeaseOperationResult.Conflict.class);

            LeaseOperationResult.Conflict conflict = (LeaseOperationResult.Conflict) result;
            assertThat(conflict.operationType()).isEqualTo("CREATE");
            assertThat(conflict.details().currentHolder())
                    .as("Current holder должен быть IDENTITY_1")
                    .isEqualTo(IDENTITY_1);
            assertThat(conflict.details().currentVersion())
                    .as("Version должна быть 1")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("Должен создать lease с минимальным TTL (1 секунда)")
        void shouldCreateLeaseWithMinimumTTL() {
            // Given: Минимальный TTL
            Duration minTtl = Duration.ofSeconds(1);

            // When: Создаём lease с минимальным TTL
            LeaseOperationResult result = scriptExecutor.createLease(
                    TEST_LEASE_KEY,
                    IDENTITY_1,
                    minTtl,
                    DEFAULT_LEASE_DURATION
            );

            // Then: Успешно создан
            assertThat(result).isInstanceOf(LeaseOperationResult.Success.class);
            assertThat(testSupport.exists(TEST_LEASE_KEY)).isTrue();
        }

        @Test
        @DisplayName("Должен создать lease с максимальным TTL (300 секунд)")
        void shouldCreateLeaseWithMaximumTTL() {
            // Given: Максимальный TTL
            Duration maxTtl = Duration.ofSeconds(300);

            // When: Создаём lease с максимальным TTL
            LeaseOperationResult result = scriptExecutor.createLease(
                    TEST_LEASE_KEY,
                    IDENTITY_1,
                    maxTtl,
                    DEFAULT_LEASE_DURATION
            );

            // Then: Успешно создан
            assertThat(result).isInstanceOf(LeaseOperationResult.Success.class);

            long ttl = testSupport.getTtl(TEST_LEASE_KEY);
            assertThat(ttl)
                    .as("TTL должен быть около 300 секунд")
                    .isBetween(298L, 300L);
        }
    }

    @Nested
    @DisplayName("Операции обновления Lease")
    @Order(2)
    class UpdateLeaseTests {

        @Test
        @DisplayName("Должен успешно обновить lease с правильной версией")
        void shouldUpdateLeaseWithCorrectVersion() {
            // Given: Существующий lease
            LeaseOperationResult createResult = scriptExecutor.createLease(
                    TEST_LEASE_KEY,
                    IDENTITY_1,
                    DEFAULT_TTL,
                    DEFAULT_LEASE_DURATION
            );

            if (!(createResult instanceof LeaseOperationResult.Success createSuccess)) {
                throw new AssertionError("Expected Success but got: " + createResult.getClass().getSimpleName());
            }

            Long versionObj = createSuccess.data().version();
            if (versionObj == null) {
                throw new AssertionError("Version is null");
            }
            long version = versionObj;

            // When: Обновляем lease с правильной версией
            LeaseOperationResult result = scriptExecutor.updateLease(
                    TEST_LEASE_KEY,
                    IDENTITY_1,
                    version,
                    DEFAULT_TTL,
                    DEFAULT_LEASE_DURATION
            );

            // Then: Обновление успешно
            assertThat(result).isInstanceOf(LeaseOperationResult.Success.class);

            LeaseOperationResult.Success success = (LeaseOperationResult.Success) result;
            assertThat(success.operationType()).isEqualTo("UPDATE");
            assertThat(success.data().version())
                    .as("Version должна увеличиться")
                    .isEqualTo(version + 1);
        }

        @Test
        @DisplayName("Должен вернуть Conflict при неправильной версии")
        void shouldReturnConflictOnVersionMismatch() {
            // Given: Существующий lease с версией 1
            scriptExecutor.createLease(TEST_LEASE_KEY, IDENTITY_1, DEFAULT_TTL, DEFAULT_LEASE_DURATION);

            // When: Обновляем с неправильной версией
            LeaseOperationResult result = scriptExecutor.updateLease(
                    TEST_LEASE_KEY,
                    IDENTITY_1,
                    999L, // Неправильная версия
                    DEFAULT_TTL,
                    DEFAULT_LEASE_DURATION
            );

            // Then: Должен вернуть Conflict
            assertThat(result).isInstanceOf(LeaseOperationResult.Conflict.class);

            LeaseOperationResult.Conflict conflict = (LeaseOperationResult.Conflict) result;
            assertThat(conflict.operationType()).isEqualTo("UPDATE");
        }

        @Test
        @DisplayName("Должен вернуть Conflict при попытке обновить чужой lease")
        void shouldReturnConflictWhenUpdatingOthersLease() {
            // Given: Lease принадлежит IDENTITY_1
            LeaseOperationResult createResult = scriptExecutor.createLease(
                    TEST_LEASE_KEY,
                    IDENTITY_1,
                    DEFAULT_TTL,
                    DEFAULT_LEASE_DURATION
            );

            if (!(createResult instanceof LeaseOperationResult.Success successCreateResult)) {
                throw new AssertionError("Expected Success but got: " + createResult.getClass().getSimpleName());
            }

            Long versionObj = successCreateResult.data().version();
            if (versionObj == null) {
                throw new AssertionError("Version is null");
            }
            long version = versionObj;

            // When: IDENTITY_2 пытается обновить
            LeaseOperationResult result = scriptExecutor.updateLease(
                    TEST_LEASE_KEY,
                    IDENTITY_2,
                    version,
                    DEFAULT_TTL,
                    DEFAULT_LEASE_DURATION
            );

            // Then: Должен вернуть Conflict (ownership mismatch)
            assertThat(result).isInstanceOf(LeaseOperationResult.Conflict.class);
        }

        @Test
        @DisplayName("Должен вернуть NotFound для несуществующего lease")
        void shouldReturnNotFoundForNonExistentLease() {
            // Given: Lease не существует
            assertThat(testSupport.exists(TEST_LEASE_KEY)).isFalse();

            // When: Пытаемся обновить
            LeaseOperationResult result = scriptExecutor.updateLease(
                    TEST_LEASE_KEY,
                    IDENTITY_1,
                    1L,
                    DEFAULT_TTL,
                    DEFAULT_LEASE_DURATION
            );

            // Then: Должен вернуть NotFound
            assertThat(result).isInstanceOf(LeaseOperationResult.NotFound.class);

            LeaseOperationResult.NotFound notFound = (LeaseOperationResult.NotFound) result;
            assertThat(notFound.operationType()).isEqualTo("UPDATE");
            assertThat(notFound.leaseKey()).isEqualTo(TEST_LEASE_KEY);
        }

        @Test
        @DisplayName("Должен успешно продлить TTL при обновлении")
        void shouldRenewTTLOnUpdate() throws InterruptedException {
            // Given: Lease с коротким TTL
            Duration shortTtl = Duration.ofSeconds(5);
            LeaseOperationResult createResult = scriptExecutor.createLease(
                    TEST_LEASE_KEY,
                    IDENTITY_1,
                    shortTtl,
                    DEFAULT_LEASE_DURATION
            );

            long initialTtl = testSupport.getTtl(TEST_LEASE_KEY);

            if (!(createResult instanceof LeaseOperationResult.Success createSuccess)) {
                throw new AssertionError("Expected Success but got: " + createResult.getClass().getSimpleName());
            }

            Long versionObj = createSuccess.data().version();
            if (versionObj == null) {
                throw new AssertionError("Version is null");
            }
            long version = versionObj;

            // Wait 2 seconds
            Thread.sleep(2000);

            // When: Обновляем с новым TTL
            Duration newTtl = Duration.ofSeconds(30);
            scriptExecutor.updateLease(
                    TEST_LEASE_KEY,
                    IDENTITY_1,
                    version,
                    newTtl,
                    DEFAULT_LEASE_DURATION
            );

            // Then: TTL должен быть продлён
            long newTtlValue = testSupport.getTtl(TEST_LEASE_KEY);
            assertThat(newTtlValue)
                    .as("TTL должен быть продлён после обновления")
                    .isGreaterThan(initialTtl);
        }
    }

    @Nested
    @DisplayName("Операции удаления Lease")
    @Order(3)
    class DeleteLeaseTests {

        @Test
        @DisplayName("Должен успешно удалить свой lease")
        void shouldDeleteOwnLease() {
            // Given: Существующий lease
            scriptExecutor.createLease(TEST_LEASE_KEY, IDENTITY_1, DEFAULT_TTL, DEFAULT_LEASE_DURATION);
            assertThat(testSupport.exists(TEST_LEASE_KEY)).isTrue();

            // When: Удаляем lease
            LeaseOperationResult result = scriptExecutor.deleteLease(TEST_LEASE_KEY, IDENTITY_1);

            // Then: Удаление успешно
            assertThat(result).isInstanceOf(LeaseOperationResult.Success.class);

            LeaseOperationResult.Success success = (LeaseOperationResult.Success) result;
            assertThat(success.operationType()).isEqualTo("DELETE");

            // And: Lease удалён из Redis
            assertThat(testSupport.exists(TEST_LEASE_KEY))
                    .as("Lease не должен существовать после удаления")
                    .isFalse();
        }

        @Test
        @DisplayName("Должен вернуть Conflict при попытке удалить чужой lease")
        void shouldReturnConflictWhenDeletingOthersLease() {
            // Given: Lease принадлежит IDENTITY_1
            scriptExecutor.createLease(TEST_LEASE_KEY, IDENTITY_1, DEFAULT_TTL, DEFAULT_LEASE_DURATION);

            // When: IDENTITY_2 пытается удалить
            LeaseOperationResult result = scriptExecutor.deleteLease(TEST_LEASE_KEY, IDENTITY_2);

            // Then: Должен вернуть Conflict
            assertThat(result).isInstanceOf(LeaseOperationResult.Conflict.class);

            LeaseOperationResult.Conflict conflict = (LeaseOperationResult.Conflict) result;
            assertThat(conflict.operationType()).isEqualTo("DELETE");
            assertThat(conflict.details().currentHolder()).isEqualTo(IDENTITY_1);

            // And: Lease всё ещё существует
            assertThat(testSupport.exists(TEST_LEASE_KEY)).isTrue();
        }

        @Test
        @DisplayName("Должен вернуть NotFound для несуществующего lease")
        void shouldReturnNotFoundWhenDeletingNonExistentLease() {
            // Given: Lease не существует
            assertThat(testSupport.exists(TEST_LEASE_KEY)).isFalse();

            // When: Пытаемся удалить
            LeaseOperationResult result = scriptExecutor.deleteLease(TEST_LEASE_KEY, IDENTITY_1);

            // Then: Должен вернуть NotFound
            assertThat(result).isInstanceOf(LeaseOperationResult.NotFound.class);
        }
    }

    @Nested
    @DisplayName("Операции получения информации о Lease")
    @Order(4)
    class GetLeaseInfoTests {

        @Test
        @DisplayName("Должен получить информацию о существующем lease")
        void shouldGetInfoForExistingLease() {
            // Given: Существующий lease
            scriptExecutor.createLease(TEST_LEASE_KEY, IDENTITY_1, DEFAULT_TTL, DEFAULT_LEASE_DURATION);

            // When: Получаем информацию
            LeaseOperationResult result = scriptExecutor.getLeaseInfo(TEST_LEASE_KEY, null);

            // Then: Информация получена
            assertThat(result).isInstanceOf(LeaseOperationResult.Success.class);

            LeaseOperationResult.Success success = (LeaseOperationResult.Success) result;
            assertThat(success.operationType()).isEqualTo("GET");
            assertThat(success.data().holder()).isEqualTo(IDENTITY_1);
            assertThat(success.data().version()).isEqualTo(1L);
            assertThat(success.data().leaseDuration()).isEqualTo(DEFAULT_LEASE_DURATION);
            assertThat(success.data().ttlRemaining()).isNotNull();
        }

        @Test
        @DisplayName("Должен вернуть NotFound для несуществующего lease")
        void shouldReturnNotFoundForNonExistentLease() {
            // Given: Lease не существует
            assertThat(testSupport.exists(TEST_LEASE_KEY)).isFalse();

            // When: Получаем информацию
            LeaseOperationResult result = scriptExecutor.getLeaseInfo(TEST_LEASE_KEY, null);

            // Then: Должен вернуть NotFound
            assertThat(result).isInstanceOf(LeaseOperationResult.NotFound.class);

            LeaseOperationResult.NotFound notFound = (LeaseOperationResult.NotFound) result;
            assertThat(notFound.operationType()).isEqualTo("GET");
        }

        @Test
        @DisplayName("Должен продлить TTL при получении информации с refreshTtl")
        void shouldRefreshTTLWhenGettingInfo() throws InterruptedException {
            // Given: Lease с коротким TTL
            Duration shortTtl = Duration.ofSeconds(5);
            scriptExecutor.createLease(TEST_LEASE_KEY, IDENTITY_1, shortTtl, DEFAULT_LEASE_DURATION);

            long initialTtl = testSupport.getTtl(TEST_LEASE_KEY);

            // Wait 2 seconds
            Thread.sleep(2000);

            // When: Получаем информацию с refreshTtl
            Duration refreshTtl = Duration.ofSeconds(30);
            scriptExecutor.getLeaseInfo(TEST_LEASE_KEY, refreshTtl);

            // Then: TTL должен быть продлён
            long newTtl = testSupport.getTtl(TEST_LEASE_KEY);
            assertThat(newTtl)
                    .as("TTL должен быть продлён при получении информации")
                    .isGreaterThan(initialTtl);
        }
    }

    @Nested
    @DisplayName("Операции захвата или продления")
    @Order(5)
    class AcquireOrRenewTests {

        @Test
        @DisplayName("Должен создать lease если его не существует")
        void shouldCreateLeaseIfNotExists() {
            // Given: Lease не существует
            assertThat(testSupport.exists(TEST_LEASE_KEY)).isFalse();

            // When: Acquire or renew
            LeaseOperationResult result = scriptExecutor.acquireOrRenewLease(
                    TEST_LEASE_KEY,
                    IDENTITY_1,
                    DEFAULT_TTL,
                    DEFAULT_LEASE_DURATION
            );

            // Then: Lease создан
            assertThat(result).isInstanceOf(LeaseOperationResult.Success.class);

            LeaseOperationResult.Success success = (LeaseOperationResult.Success) result;
            assertThat(success.operationType()).isEqualTo("ACQUIRE_OR_RENEW");
            assertThat(success.data().version()).isEqualTo(1L);
            assertThat(testSupport.exists(TEST_LEASE_KEY)).isTrue();
        }

        @Test
        @DisplayName("Должен продлить свой существующий lease")
        void shouldRenewOwnLease() {
            // Given: Существующий lease от IDENTITY_1
            scriptExecutor.createLease(TEST_LEASE_KEY, IDENTITY_1, DEFAULT_TTL, DEFAULT_LEASE_DURATION);

            // When: Acquire or renew тем же identity
            LeaseOperationResult result = scriptExecutor.acquireOrRenewLease(
                    TEST_LEASE_KEY,
                    IDENTITY_1,
                    DEFAULT_TTL,
                    DEFAULT_LEASE_DURATION
            );

            // Then: Lease продлён
            assertThat(result).isInstanceOf(LeaseOperationResult.Success.class);

            LeaseOperationResult.Success success = (LeaseOperationResult.Success) result;
            assertThat(success.data().version())
                    .as("Version должна увеличиться при renewal")
                    .isEqualTo(2L);
        }

        @Test
        @DisplayName("Должен вернуть Conflict если lease принадлежит другому")
        void shouldReturnConflictIfLeaseOwnedByOther() {
            // Given: Lease принадлежит IDENTITY_1
            scriptExecutor.createLease(TEST_LEASE_KEY, IDENTITY_1, DEFAULT_TTL, DEFAULT_LEASE_DURATION);

            // When: IDENTITY_2 пытается acquire or renew
            LeaseOperationResult result = scriptExecutor.acquireOrRenewLease(
                    TEST_LEASE_KEY,
                    IDENTITY_2,
                    DEFAULT_TTL,
                    DEFAULT_LEASE_DURATION
            );

            // Then: Должен вернуть Conflict
            assertThat(result).isInstanceOf(LeaseOperationResult.Conflict.class);

            LeaseOperationResult.Conflict conflict = (LeaseOperationResult.Conflict) result;
            assertThat(conflict.details().currentHolder()).isEqualTo(IDENTITY_1);
        }
    }

    @Nested
    @DisplayName("Валидация TTL")
    @Order(6)
    class TTLValidationTests {

        @Test
        @DisplayName("Должен отклонить TTL меньше минимального (< 1 сек)")
        void shouldRejectTTLBelowMinimum() {
            // Given: TTL меньше минимума
            Duration invalidTtl = Duration.ofMillis(500);

            // When & Then: Должен выбросить исключение
            assertThatThrownBy(() ->
                    scriptExecutor.createLease(TEST_LEASE_KEY, IDENTITY_1, invalidTtl, DEFAULT_LEASE_DURATION)
            )
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("TTL must be between");
        }

        @Test
        @DisplayName("Должен отклонить TTL больше максимального (> 300 сек)")
        void shouldRejectTTLAboveMaximum() {
            // Given: TTL больше максимума
            Duration invalidTtl = Duration.ofSeconds(301);

            // When & Then: Должен выбросить исключение
            assertThatThrownBy(() ->
                    scriptExecutor.createLease(TEST_LEASE_KEY, IDENTITY_1, invalidTtl, DEFAULT_LEASE_DURATION)
            )
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("TTL must be between");
        }

        @Test
        @DisplayName("Должен принять граничный минимальный TTL (1 сек)")
        void shouldAcceptBoundaryMinimumTTL() {
            // Given: Минимальный валидный TTL
            Duration minTtl = Duration.ofSeconds(1);

            // When: Создаём lease
            LeaseOperationResult result = scriptExecutor.createLease(
                    TEST_LEASE_KEY,
                    IDENTITY_1,
                    minTtl,
                    DEFAULT_LEASE_DURATION
            );

            // Then: Должен успешно создаться
            assertThat(result).isInstanceOf(LeaseOperationResult.Success.class);
        }

        @Test
        @DisplayName("Должен принять граничный максимальный TTL (300 сек)")
        void shouldAcceptBoundaryMaximumTTL() {
            // Given: Максимальный валидный TTL
            Duration maxTtl = Duration.ofSeconds(300);

            // When: Создаём lease
            LeaseOperationResult result = scriptExecutor.createLease(
                    TEST_LEASE_KEY,
                    IDENTITY_1,
                    maxTtl,
                    DEFAULT_LEASE_DURATION
            );

            // Then: Должен успешно создаться
            assertThat(result).isInstanceOf(LeaseOperationResult.Success.class);
        }
    }

    @Nested
    @DisplayName("Параллельные операции")
    @Order(7)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    class ConcurrentOperationsTests {

        @Test
        @DisplayName("Должен обрабатывать параллельные попытки создания lease")
        void shouldHandleConcurrentCreateAttempts() throws InterruptedException {
            // Given: 5 потоков пытаются создать один и тот же lease
            int threadCount = 5;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch finishLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger conflictCount = new AtomicInteger(0);

            // When: Все потоки одновременно пытаются создать lease
            for (int i = 0; i < threadCount; i++) {
                final String identity = "thread-" + i;
                new Thread(() -> {
                    try {
                        startLatch.await();

                        LeaseOperationResult result = scriptExecutor.createLease(
                                TEST_LEASE_KEY,
                                identity,
                                DEFAULT_TTL,
                                DEFAULT_LEASE_DURATION
                        );

                        if (result instanceof LeaseOperationResult.Success) {
                            successCount.incrementAndGet();
                        } else if (result instanceof LeaseOperationResult.Conflict) {
                            conflictCount.incrementAndGet();
                        }

                    } catch (Exception ignored) {
                    } finally {
                        finishLatch.countDown();
                    }
                }).start();
            }

            startLatch.countDown(); // Start all threads
            boolean completed = finishLatch.await(10, TimeUnit.SECONDS);

            // Then: Все потоки завершились
            assertThat(completed).isTrue();

            // And: Только один успешно создал lease
            assertThat(successCount.get())
                    .as("Только один поток должен успешно создать lease")
                    .isEqualTo(1);

            // And: Остальные получили Conflict
            assertThat(conflictCount.get())
                    .as("Остальные потоки должны получить Conflict")
                    .isEqualTo(threadCount - 1);

            // And: Lease существует
            assertThat(testSupport.exists(TEST_LEASE_KEY)).isTrue();
        }

        @Test
        @DisplayName("Должен обрабатывать параллельные update операции")
        void shouldHandleConcurrentUpdateOperations() throws InterruptedException {
            // Given: Существующий lease
            LeaseOperationResult createResult = scriptExecutor.createLease(
                    TEST_LEASE_KEY,
                    IDENTITY_1,
                    DEFAULT_TTL,
                    DEFAULT_LEASE_DURATION
            );

            if (!(createResult instanceof LeaseOperationResult.Success createSuccess)) {
                throw new AssertionError("Expected Success but got: " + createResult.getClass().getSimpleName());
            }

            Long versionObj = createSuccess.data().version();
            if (versionObj == null) {
                throw new AssertionError("Version is null");
            }
            long initialVersion = versionObj;

            // And: 3 потока пытаются обновить с одной и той же версией
            int threadCount = 3;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch finishLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicReference<Long> finalVersion = new AtomicReference<>(initialVersion);

            // When: Параллельные updates
            for (int i = 0; i < threadCount; i++) {
                new Thread(() -> {
                    try {
                        startLatch.await();

                        LeaseOperationResult result = scriptExecutor.updateLease(
                                TEST_LEASE_KEY,
                                IDENTITY_1,
                                initialVersion,
                                DEFAULT_TTL,
                                DEFAULT_LEASE_DURATION
                        );

                        if (result instanceof LeaseOperationResult.Success success) {
                            successCount.incrementAndGet();
                            finalVersion.set(success.data().version());
                        }
                    } catch (Exception ignored) {
                    } finally {
                        finishLatch.countDown();
                    }
                }).start();
            }

            startLatch.countDown();
            boolean completed = finishLatch.await(10, TimeUnit.SECONDS);

            // Then: Все потоки завершились
            assertThat(completed).isTrue();

            // And: Только один успешно обновил
            assertThat(successCount.get())
                    .as("Только один поток должен успешно обновить lease")
                    .isEqualTo(1);

            // And: Version увеличилась на 1
            assertThat(finalVersion.get())
                    .as("Version должна увеличиться на 1")
                    .isEqualTo(initialVersion + 1);
        }

        @Test
        @DisplayName("Должен обрабатывать параллельные acquireOrRenew операции")
        void shouldHandleConcurrentAcquireOrRenewOperations() throws InterruptedException {
            // Given: 10 потоков одновременно пытаются acquire or renew
            int threadCount = 10;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch finishLatch = new CountDownLatch(threadCount);
            AtomicInteger createCount = new AtomicInteger(0);
            AtomicInteger renewCount = new AtomicInteger(0);
            AtomicInteger conflictCount = new AtomicInteger(0);

            // When: Параллельные acquireOrRenew с разными identities
            for (int i = 0; i < threadCount; i++) {
                final String identity = "thread-" + i;
                new Thread(() -> {
                    try {
                        startLatch.await();

                        LeaseOperationResult result = scriptExecutor.acquireOrRenewLease(
                                TEST_LEASE_KEY,
                                identity,
                                DEFAULT_TTL,
                                DEFAULT_LEASE_DURATION
                        );

                        if (result instanceof LeaseOperationResult.Success success) {
                            Long version = success.data().version();
                            if (version != null && version == 1L) {
                                createCount.incrementAndGet();
                            } else {
                                renewCount.incrementAndGet();
                            }
                        } else if (result instanceof LeaseOperationResult.Conflict) {
                            conflictCount.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        finishLatch.countDown();
                    }
                }).start();
            }

            startLatch.countDown();
            boolean completed = finishLatch.await(10, TimeUnit.SECONDS);

            // Then: Все потоки завершились
            assertThat(completed).isTrue();

            // And: Только один создал lease
            assertThat(createCount.get())
                    .as("Только один поток должен создать lease")
                    .isEqualTo(1);

            // And: Остальные получили Conflict (не могут renew чужой lease)
            assertThat(conflictCount.get())
                    .as("Остальные потоки должны получить Conflict")
                    .isEqualTo(threadCount - 1);
        }
    }

    @Nested
    @DisplayName("Граничные случаи")
    @Order(8)
    class EdgeCasesTests {

        @Test
        @DisplayName("Должен обработать очень длинный identity")
        void shouldHandleVeryLongIdentity() {
            // Given: Очень длинный identity (1000 символов)
            String longIdentity = "i".repeat(1000);

            // When: Создаём lease с длинным identity
            LeaseOperationResult result = scriptExecutor.createLease(
                    TEST_LEASE_KEY,
                    longIdentity,
                    DEFAULT_TTL,
                    DEFAULT_LEASE_DURATION
            );

            // Then: Должен успешно создаться
            assertThat(result).isInstanceOf(LeaseOperationResult.Success.class);

            // And: Можем получить информацию
            LeaseOperationResult getResult = scriptExecutor.getLeaseInfo(TEST_LEASE_KEY, null);
            assertThat(getResult).isInstanceOf(LeaseOperationResult.Success.class);

            LeaseOperationResult.Success success = (LeaseOperationResult.Success) getResult;
            assertThat(success.data().holder()).isEqualTo(longIdentity);
        }

        @Test
        @DisplayName("Должен обработать identity со специальными символами")
        void shouldHandleIdentityWithSpecialCharacters() {
            // Given: Identity со специальными символами
            String specialIdentity = "instance:123-abc_def@domain.com#test";

            // When: Создаём lease
            LeaseOperationResult result = scriptExecutor.createLease(
                    TEST_LEASE_KEY,
                    specialIdentity,
                    DEFAULT_TTL,
                    DEFAULT_LEASE_DURATION
            );

            // Then: Должен успешно создаться
            assertThat(result).isInstanceOf(LeaseOperationResult.Success.class);

            // And: Identity сохранён корректно
            LeaseOperationResult getResult = scriptExecutor.getLeaseInfo(TEST_LEASE_KEY, null);
            LeaseOperationResult.Success success = (LeaseOperationResult.Success) getResult;
            assertThat(success.data().holder()).isEqualTo(specialIdentity);
        }

        @Test
        @DisplayName("Должен обработать очень большой leaseDuration")
        void shouldHandleVeryLargeLeaseDuration() {
            // Given: Очень большой leaseDuration
            int largeDuration = Integer.MAX_VALUE;

            // When: Создаём lease
            LeaseOperationResult result = scriptExecutor.createLease(
                    TEST_LEASE_KEY,
                    IDENTITY_1,
                    DEFAULT_TTL,
                    largeDuration
            );

            // Then: Должен успешно создаться
            assertThat(result).isInstanceOf(LeaseOperationResult.Success.class);

            // And: Duration сохранён
            LeaseOperationResult getResult = scriptExecutor.getLeaseInfo(TEST_LEASE_KEY, null);
            LeaseOperationResult.Success success = (LeaseOperationResult.Success) getResult;
            assertThat(success.data().leaseDuration()).isEqualTo(largeDuration);
        }

        @Test
        @DisplayName("Должен обработать множественные последовательные обновления")
        void shouldHandleMultipleSequentialUpdates() {
            // Given: Существующий lease
            scriptExecutor.createLease(TEST_LEASE_KEY, IDENTITY_1, DEFAULT_TTL, DEFAULT_LEASE_DURATION);

            // When: Выполняем 10 последовательных обновлений
            long currentVersion = 1L;
            for (int i = 0; i < 10; i++) {
                LeaseOperationResult result = scriptExecutor.updateLease(
                        TEST_LEASE_KEY,
                        IDENTITY_1,
                        currentVersion,
                        DEFAULT_TTL,
                        DEFAULT_LEASE_DURATION
                );

                if (!(result instanceof LeaseOperationResult.Success success)) {
                    throw new AssertionError("Expected Success but got: " + result.getClass().getSimpleName());
                }

                Long versionObj = success.data().version();
                if (versionObj == null) {
                    throw new AssertionError("Version is null");
                }
                currentVersion = versionObj;
            }

            // Then: Финальная версия должна быть 11
            assertThat(currentVersion).isEqualTo(11L);
        }

        @Test
        @DisplayName("Должен обработать быстрые циклы создания-удаления")
        void shouldHandleRapidCreateDeleteCycles() {
            // When: Выполняем 5 циклов create-delete
            for (int i = 0; i < 5; i++) {
                // Create
                LeaseOperationResult createResult = scriptExecutor.createLease(
                        TEST_LEASE_KEY,
                        IDENTITY_1,
                        DEFAULT_TTL,
                        DEFAULT_LEASE_DURATION
                );
                assertThat(createResult).isInstanceOf(LeaseOperationResult.Success.class);

                // Delete
                LeaseOperationResult deleteResult = scriptExecutor.deleteLease(TEST_LEASE_KEY, IDENTITY_1);
                assertThat(deleteResult).isInstanceOf(LeaseOperationResult.Success.class);
            }

            // Then: В конце lease не должен существовать
            assertThat(testSupport.exists(TEST_LEASE_KEY)).isFalse();
        }
    }

    @Nested
    @DisplayName("Управление версиями")
    @Order(9)
    class VersionManagementTests {

        @Test
        @DisplayName("Должен увеличивать версию при каждом обновлении")
        void shouldIncrementVersionOnEachUpdate() {
            // Given: Новый lease (version 1)
            scriptExecutor.createLease(TEST_LEASE_KEY, IDENTITY_1, DEFAULT_TTL, DEFAULT_LEASE_DURATION);

            // When: Обновляем 3 раза
            long version = 1L;
            for (int i = 0; i < 3; i++) {
                LeaseOperationResult result = scriptExecutor.updateLease(
                        TEST_LEASE_KEY,
                        IDENTITY_1,
                        version,
                        DEFAULT_TTL,
                        DEFAULT_LEASE_DURATION
                );

                if (!(result instanceof LeaseOperationResult.Success success)) {
                    throw new AssertionError("Expected Success but got: " + result.getClass().getSimpleName());
                }

                Long versionObj = success.data().version();
                if (versionObj == null) {
                    throw new AssertionError("Version is null");
                }
                version = versionObj;

                assertThat(version).isEqualTo(2L + i);
            }

            // Then: Финальная версия = 4
            assertThat(version).isEqualTo(4L);
        }

        @Test
        @DisplayName("Должен увеличивать версию при acquireOrRenew")
        void shouldIncrementVersionOnAcquireOrRenew() {
            // Given: Новый lease через acquireOrRenew
            LeaseOperationResult result1 = scriptExecutor.acquireOrRenewLease(
                    TEST_LEASE_KEY,
                    IDENTITY_1,
                    DEFAULT_TTL,
                    DEFAULT_LEASE_DURATION
            );

            assertThat(((LeaseOperationResult.Success) result1).data().version()).isEqualTo(1L);

            // When: Renewal того же lease
            LeaseOperationResult result2 = scriptExecutor.acquireOrRenewLease(
                    TEST_LEASE_KEY,
                    IDENTITY_1,
                    DEFAULT_TTL,
                    DEFAULT_LEASE_DURATION
            );

            // Then: Версия увеличилась
            assertThat(((LeaseOperationResult.Success) result2).data().version()).isEqualTo(2L);
        }

        @Test
        @DisplayName("Должен сбросить версию при повторном создании после удаления")
        void shouldResetVersionAfterDeletion() {
            // Given: Lease с версией 3
            scriptExecutor.createLease(TEST_LEASE_KEY, IDENTITY_1, DEFAULT_TTL, DEFAULT_LEASE_DURATION);
            scriptExecutor.updateLease(TEST_LEASE_KEY, IDENTITY_1, 1L, DEFAULT_TTL, DEFAULT_LEASE_DURATION);
            scriptExecutor.updateLease(TEST_LEASE_KEY, IDENTITY_1, 2L, DEFAULT_TTL, DEFAULT_LEASE_DURATION);

            // When: Удаляем и создаём заново
            scriptExecutor.deleteLease(TEST_LEASE_KEY, IDENTITY_1);
            LeaseOperationResult newResult = scriptExecutor.createLease(
                    TEST_LEASE_KEY,
                    IDENTITY_2,
                    DEFAULT_TTL,
                    DEFAULT_LEASE_DURATION
            );

            // Then: Версия снова 1
            LeaseOperationResult.Success success = (LeaseOperationResult.Success) newResult;
            assertThat(success.data().version()).isEqualTo(1L);
        }
    }

}
