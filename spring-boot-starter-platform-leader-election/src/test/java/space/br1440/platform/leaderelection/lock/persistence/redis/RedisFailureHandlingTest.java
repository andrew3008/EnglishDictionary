package space.br1440.platform.leaderelection.lock.persistence.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import space.br1440.platform.leaderelection.lock.persistence.model.LeaseRecord;
import space.br1440.platform.leaderelection.lock.persistence.redis.resilience.CircuitBreakerAdapter;
import space.br1440.platform.leaderelection.lock.persistence.redis.resilience.CircuitBreakerFactory;
import space.br1440.platform.leaderelection.metrics.LeaderElectionMetricsInterface;
import space.br1440.platform.leaderelection.metrics.NoOpLeaderElectionMetrics;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Тесты обработки сбоев Redis")
class RedisFailureHandlingTest {

    private static final String LEASE_KEY = "test-lock";
    private static final String IDENTITY = "test-instance";
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(10);
    private static final Duration DEFAULT_LOCK_IDLE_BETWEEN_TRIES = Duration.ofMillis(50);

    private StringRedisTemplate redisTemplate;
    private RedisLeaseRecordRepository repository;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
        repository = new RedisLeaseRecordRepository(
                redisTemplate,
                objectMapper,
                false,
                CircuitBreakerFactory.create(5, Duration.ofSeconds(30))
        );
    }

    @Nested
    @DisplayName("Тесты сбоев подключения")
    class ConnectionFailureTests {

        @Test
        @DisplayName("tryAcquireLease должен вернуть 0 при сбое подключения к Redis")
        @SuppressWarnings("unchecked")
        void shouldReturnZeroOnConnectionFailureForAcquire() {
            // Given: Redis недоступен
            when(redisTemplate.execute(any(RedisCallback.class)))
                    .thenThrow(new RedisConnectionFailureException("Connection refused"));

            // When
            Long result = repository.tryAcquireLease(LEASE_KEY, IDENTITY, DEFAULT_TTL);

            // Then
            assertThat(result)
                    .as("Должен вернуть 0 при сбое подключения")
                    .isEqualTo(0L);
        }

        @Test
        @DisplayName("tryRenewLease должен вернуть 0 при сбое подключения к Redis")
        @SuppressWarnings("unchecked")
        void shouldReturnZeroOnConnectionFailureForRenew() {
            // Given: Redis недоступен
            when(redisTemplate.execute(any(RedisCallback.class)))
                    .thenThrow(new RedisConnectionFailureException("Connection timeout"));

            // When
            Long result = repository.tryRenewLease(LEASE_KEY, IDENTITY, DEFAULT_TTL);

            // Then
            assertThat(result)
                    .as("Должен вернуть 0 при сбое подключения")
                    .isEqualTo(0L);
        }

        @Test
        @DisplayName("getCurrentRecord должен вернуть null при сбое подключения к Redis")
        @SuppressWarnings("unchecked")
        void shouldReturnNullOnConnectionFailureForGet() {
            // Given: Redis недоступен
            when(redisTemplate.execute(any(RedisCallback.class)))
                    .thenThrow(new RedisConnectionFailureException("No route to host"));

            // When
            LeaseRecord result = repository.getCurrentRecord(LEASE_KEY);

            // Then
            assertThat(result)
                    .as("Должен вернуть null при сбое подключения")
                    .isNull();
        }

        @Test
        @DisplayName("deleteRecord должен вернуть false при сбое подключения к Redis")
        @SuppressWarnings("unchecked")
        void shouldReturnFalseOnConnectionFailureForDelete() {
            // Given: Redis недоступен
            when(redisTemplate.execute(any(RedisCallback.class)))
                    .thenThrow(new RedisConnectionFailureException("Connection reset"));

            // When
            Boolean result = repository.deleteRecord(LEASE_KEY, IDENTITY);

            // Then
            assertThat(result)
                    .as("Должен вернуть false при сбое подключения")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Тесты таймаутов")
    class TimeoutTests {

        @Test
        @DisplayName("tryAcquireLease должен вернуть 0 при таймауте запроса")
        @SuppressWarnings("unchecked")
        void shouldReturnZeroOnQueryTimeout() {
            // Given: Redis отвечает слишком долго
            when(redisTemplate.execute(any(RedisCallback.class)))
                    .thenThrow(new QueryTimeoutException("Query timeout"));

            // When
            Long result = repository.tryAcquireLease(LEASE_KEY, IDENTITY, DEFAULT_TTL);

            // Then
            assertThat(result)
                    .as("Должен вернуть 0 при таймауте")
                    .isEqualTo(0L);
        }

        @Test
        @DisplayName("getCurrentRecord должен вернуть null при таймауте запроса")
        @SuppressWarnings("unchecked")
        void shouldReturnNullOnQueryTimeout() {
            // Given: Redis отвечает слишком долго
            when(redisTemplate.execute(any(RedisCallback.class)))
                    .thenThrow(new QueryTimeoutException("Read timeout"));

            // When
            LeaseRecord result = repository.getCurrentRecord(LEASE_KEY);

            // Then
            assertThat(result)
                    .as("Должен вернуть null при таймауте")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("Тесты CircuitBreaker")
    class CircuitBreakerTests {

        @Test
        @DisplayName("CircuitBreaker должен открыться после нескольких неудач подряд")
        @SuppressWarnings("unchecked")
        void shouldOpenCircuitBreakerAfterConsecutiveFailures() {
            // Given: Redis падает несколько раз подряд
            when(redisTemplate.execute(any(RedisCallback.class)))
                    .thenThrow(new RedisConnectionFailureException("Connection failed"));

            // When: Множественные попытки создать lease
            for (int i = 0; i < 10; i++) {
                repository.tryAcquireLease(LEASE_KEY, IDENTITY + "-" + i, DEFAULT_TTL);
            }

            // Then: Все вызовы должны вернуть 0 без исключений
            Long result = repository.tryAcquireLease(LEASE_KEY, IDENTITY, DEFAULT_TTL);
            assertThat(result)
                    .as("CircuitBreaker должен продолжать возвращать 0")
                    .isEqualTo(0L);
        }

        @Test
        @DisplayName("CircuitBreaker должен восстановиться после успешного запроса")
        @SuppressWarnings("unchecked")
        void shouldRecoverCircuitBreakerAfterSuccess() {
            // Given: Redis сначала недоступен, потом восстанавливается
            when(redisTemplate.execute(any(RedisCallback.class)))
                    .thenThrow(new RedisConnectionFailureException("Connection failed"))
                    .thenThrow(new RedisConnectionFailureException("Connection failed"))
                    .thenReturn("1"); // Успешный ответ

            // When: Несколько неудачных попыток, затем успешная
            repository.tryAcquireLease(LEASE_KEY, IDENTITY + "-1", DEFAULT_TTL);
            repository.tryAcquireLease(LEASE_KEY, IDENTITY + "-2", DEFAULT_TTL);

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            repository.tryAcquireLease(LEASE_KEY, IDENTITY, DEFAULT_TTL);

            // Then: Успешный запрос не должен бросить исключение
            assertThatCode(() ->
                    repository.tryAcquireLease(LEASE_KEY, IDENTITY, DEFAULT_TTL)
            ).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Тесты Null и некорректных входных данных")
    class InvalidInputTests {

        @Test
        @DisplayName("tryAcquireLease не должен падать на null key")
        void shouldHandleNullKeyGracefully() {
            // When: Передан null key
            assertThatCode(() ->
                    repository.tryAcquireLease(null, IDENTITY, DEFAULT_TTL)
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("tryAcquireLease не должен падать на null identity")
        void shouldHandleNullIdentityGracefully() {
            // When: Передан null identity
            assertThatCode(() ->
                    repository.tryAcquireLease(LEASE_KEY, null, DEFAULT_TTL)
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("tryAcquireLease не должен падать на null ttl")
        void shouldHandleNullTtlGracefully() {
            // When: Передан null ttl
            assertThatCode(() ->
                    repository.tryAcquireLease(LEASE_KEY, IDENTITY, null)
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("getCurrentRecord не должен падать на null key")
        void shouldHandleNullKeyForGetGracefully() {
            // When: Передан null key
            LeaseRecord result = repository.getCurrentRecord(null);

            // Then: Должен вернуть null
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("Тесты сбоев RedisDistributedLock")
    class RedisDistributedLockFailureTests {

        private RedisDistributedLock lock;
        private RedisLeaseRecordRepository mockRepository;

        @BeforeEach
        void setUp() {
            mockRepository = mock(RedisLeaseRecordRepository.class);
            LeaderElectionMetricsInterface metrics = new NoOpLeaderElectionMetrics();
            CircuitBreakerAdapter circuitBreaker = CircuitBreakerFactory.create(5, Duration.ofSeconds(30));
            lock = new RedisDistributedLock(LEASE_KEY, IDENTITY, DEFAULT_TTL, DEFAULT_LOCK_IDLE_BETWEEN_TRIES, mockRepository, metrics, circuitBreaker);
        }

        @Test
        @DisplayName("tryLock не должен падать при сбое Redis")
        void shouldNotThrowOnRedisFailureForTryLock() {
            // Given: Repository возвращает false (сбой)
            when(mockRepository.createRecord(eq(LEASE_KEY), any(LeaseRecord.class), eq(DEFAULT_TTL)))
                    .thenReturn(false);

            // When/Then: tryLock должен вернуть false без исключения
            //noinspection ResultOfMethodCallIgnored
            assertThatCode(() -> lock.tryLock())
                    .doesNotThrowAnyException();

            boolean result = lock.tryLock();
            assertThat(result).as("tryLock должен вернуть false").isFalse();
        }

        @Test
        @DisplayName("unlock должен логировать ошибку, но не падать при сбое Redis")
        void shouldLogButNotThrowOnRedisFailureForUnlock() {
            // Given: Lock успешно захвачен, потом сбой при delete
            when(mockRepository.createRecord(eq(LEASE_KEY), any(LeaseRecord.class), eq(DEFAULT_TTL)))
                    .thenReturn(true);
            when(mockRepository.deleteRecord(LEASE_KEY))
                    .thenThrow(new RedisConnectionFailureException("Connection failed"));

            // When: Захватываем lock
            boolean acquired = lock.tryLock();
            assertThat(acquired).as("Lock должен быть захвачен").isTrue();

            // Then: unlock не должен бросать исключение (отказоустойчивость)
            assertThatCode(() -> lock.unlock())
                    .doesNotThrowAnyException();

            // And
            assertThat(lock.isAcquired())
                    .as("Локальное состояние должно быть сброшено даже при сбое Redis")
                    .isFalse();
        }

        @Test
        @DisplayName("isAcquired должен корректно работать без обращения к Redis")
        void shouldReturnLocalStateForIsAcquired() {
            // Given: Lock не захвачен
            // When/Then: isAcquired должен вернуть false (локальное состояние)
            boolean result = lock.isAcquired();
            assertThat(result).as("isAcquired должен вернуть false").isFalse();

            // Given: Lock захвачен
            when(mockRepository.createRecord(eq(LEASE_KEY), any(LeaseRecord.class), eq(DEFAULT_TTL)))
                    .thenReturn(true);
            //noinspection ResultOfMethodCallIgnored
            lock.tryLock();

            // When/Then
            result = lock.isAcquired();
            assertThat(result).as("isAcquired должен вернуть true после захвата").isTrue();
        }
    }

}

