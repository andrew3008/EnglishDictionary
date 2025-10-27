package space.br1440.platform.leaderelection.lock.persistence.redis.resilience;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CircuitBreaker Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CircuitBreakerTest {

    private static final int TEST_FAILURE_THRESHOLD = 3;
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

    private TestClock testClock;
    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        testClock = new TestClock();
        circuitBreaker = CircuitBreaker.builder()
                .failureThreshold(TEST_FAILURE_THRESHOLD)
                .timeout(TEST_TIMEOUT)
                .clock(testClock)
                .build();
    }

    @Nested
    @DisplayName("1. Builder Validation")
    @Order(1)
    class BuilderValidationTests {

        @Test
        @DisplayName("Должен создать CircuitBreaker с default значениями")
        void shouldCreateWithDefaultValues() {
            // When
            CircuitBreaker cb = CircuitBreaker.builder().build();

            // Then
            assertThat(cb.getState()).isEqualTo(CircuitBreakerState.CLOSED);
            assertThat(cb.getFailureCount()).isZero();
            assertThat(cb.getFailureThreshold()).isEqualTo(CircuitBreaker.DEFAULT_FAILURE_THRESHOLD);
        }

        @Test
        @DisplayName("Должен создать CircuitBreaker с custom значениями")
        void shouldCreateWithCustomValues() {
            // Given
            int customThreshold = 10;
            Duration customTimeout = Duration.ofMinutes(5);

            // When
            CircuitBreaker cb = CircuitBreaker.builder()
                    .failureThreshold(customThreshold)
                    .timeout(customTimeout)
                    .build();

            // Then
            assertThat(cb.getFailureThreshold()).isEqualTo(customThreshold);
            assertThat(cb.getState()).isEqualTo(CircuitBreakerState.CLOSED);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -10, 101, 1000})
        @DisplayName("Должен выбросить IllegalArgumentException для невалидного failureThreshold")
        void shouldRejectInvalidFailureThreshold(int invalidThreshold) {
            // When & Then
            assertThatThrownBy(() ->
                    CircuitBreaker.builder()
                            .failureThreshold(invalidThreshold)
                            .build()
            )
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Failure threshold must be between");
        }

        @Test
        @DisplayName("Должен выбросить IllegalArgumentException для null timeout")
        void shouldRejectNullTimeout() {
            // When & Then
            assertThatThrownBy(() ->
                    CircuitBreaker.builder()
                            .timeout(null)
                            .build()
            )
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Timeout cannot be null");
        }

        @ParameterizedTest
        @ValueSource(longs = {0, 50, 99})
        @DisplayName("Должен выбросить IllegalArgumentException для слишком короткого timeout")
        void shouldRejectTooShortTimeout(long millis) {
            // When & Then
            assertThatThrownBy(() ->
                    CircuitBreaker.builder()
                            .timeout(Duration.ofMillis(millis))
                            .build()
            )
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Timeout must be between");
        }

        @Test
        @DisplayName("Должен выбросить IllegalArgumentException для слишком длинного timeout")
        void shouldRejectTooLongTimeout() {
            // When & Then
            assertThatThrownBy(() ->
                    CircuitBreaker.builder()
                            .timeout(Duration.ofHours(2))
                            .build()
            )
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Timeout must be between");
        }

        @Test
        @DisplayName("Должен выбросить IllegalArgumentException для null clock")
        void shouldRejectNullClock() {
            // When & Then
            assertThatThrownBy(() ->
                    CircuitBreaker.builder()
                            .clock(null)
                            .build()
            )
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Clock cannot be null");
        }

        @Test
        @DisplayName("Должен принять граничные валидные значения")
        void shouldAcceptBoundaryValidValues() {
            // When: минимальные валидные значения
            CircuitBreaker cbMin = CircuitBreaker.builder()
                    .failureThreshold(CircuitBreaker.MIN_FAILURE_THRESHOLD)
                    .timeout(CircuitBreaker.MIN_TIMEOUT)
                    .build();

            // Then
            assertThat(cbMin.getFailureThreshold()).isEqualTo(1);

            // When: максимальные валидные значения
            CircuitBreaker cbMax = CircuitBreaker.builder()
                    .failureThreshold(CircuitBreaker.MAX_FAILURE_THRESHOLD)
                    .timeout(CircuitBreaker.MAX_TIMEOUT)
                    .build();

            // Then
            assertThat(cbMax.getFailureThreshold()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("2. State Transitions")
    @Order(2)
    class StateTransitionTests {

        @Test
        @DisplayName("Должен перейти из CLOSED в OPEN после N failures")
        void shouldTransitionFromClosedToOpenAfterFailures() {
            // Given: Circuit Breaker в состоянии CLOSED
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);
            assertThat(circuitBreaker.allowRequest()).isTrue();

            // When: Записываем (failureThreshold - 1) failures
            for (int i = 0; i < TEST_FAILURE_THRESHOLD - 1; i++) {
                circuitBreaker.recordFailure();
            }

            // Then: Остаёмся в CLOSED
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);
            assertThat(circuitBreaker.getFailureCount()).isEqualTo(TEST_FAILURE_THRESHOLD - 1);
            assertThat(circuitBreaker.allowRequest()).isTrue();

            // When: Записываем failureThreshold-й failure
            circuitBreaker.recordFailure();

            // Then: Переходим в OPEN
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.OPEN);
            assertThat(circuitBreaker.getFailureCount()).isEqualTo(TEST_FAILURE_THRESHOLD);
            assertThat(circuitBreaker.allowRequest()).isFalse();
        }

        @Test
        @DisplayName("Должен перейти из OPEN в HALF_OPEN после timeout")
        void shouldTransitionFromOpenToHalfOpenAfterTimeout() {
            // Given: Circuit Breaker в состоянии OPEN
            openCircuitBreaker();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.OPEN);
            assertThat(circuitBreaker.allowRequest()).isFalse();

            // When: Время НЕ истекло (timeout - 1 секунда)
            testClock.advance(TEST_TIMEOUT.minusSeconds(1));

            // Then: Остаёмся в OPEN
            assertThat(circuitBreaker.allowRequest()).isFalse();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.OPEN);

            // When: Время истекло (+ 2 секунды)
            testClock.advance(Duration.ofSeconds(2));

            // Then: Переходим в HALF_OPEN при следующем allowRequest()
            assertThat(circuitBreaker.allowRequest()).isTrue();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.HALF_OPEN);
        }

        @Test
        @DisplayName("Должен перейти из HALF_OPEN в CLOSED после success")
        void shouldTransitionFromHalfOpenToClosedOnSuccess() {
            // Given: Circuit Breaker в состоянии HALF_OPEN
            halfOpenCircuitBreaker();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.HALF_OPEN);

            // When: Записываем успех
            circuitBreaker.recordSuccess();

            // Then: Переходим в CLOSED и сбрасываем счётчик
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);
            assertThat(circuitBreaker.getFailureCount()).isZero();
            assertThat(circuitBreaker.allowRequest()).isTrue();
        }

        @Test
        @DisplayName("Должен перейти из HALF_OPEN в OPEN после failure")
        void shouldTransitionFromHalfOpenToOpenOnFailure() {
            // Given: Circuit Breaker в состоянии HALF_OPEN
            halfOpenCircuitBreaker();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.HALF_OPEN);
            int failureCountBeforeFail = circuitBreaker.getFailureCount();

            // When: Записываем failure
            circuitBreaker.recordFailure();

            // Then: Переходим обратно в OPEN
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.OPEN);
            assertThat(circuitBreaker.getFailureCount()).isEqualTo(failureCountBeforeFail + 1);
            assertThat(circuitBreaker.allowRequest()).isFalse();
        }

        @Test
        @DisplayName("Должен сбросить failureCount при успехе в CLOSED состоянии")
        void shouldResetFailureCountOnSuccessInClosedState() {
            // Given: Несколько failures в CLOSED
            circuitBreaker.recordFailure();
            circuitBreaker.recordFailure();
            assertThat(circuitBreaker.getFailureCount()).isEqualTo(2);
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);

            // When: Записываем успех
            circuitBreaker.recordSuccess();

            // Then: Счётчик сброшен, остаёмся в CLOSED
            assertThat(circuitBreaker.getFailureCount()).isZero();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);
        }
    }

    @Nested
    @DisplayName("3. Execute Method with Operation and Fallback")
    @Order(3)
    class ExecuteMethodTests {

        @Test
        @DisplayName("Должен выполнить operation и вернуть результат в CLOSED состоянии")
        void shouldExecuteOperationAndReturnResultWhenClosed() {
            // Given
            Supplier<String> operation = () -> "success";
            Supplier<String> fallback = () -> "fallback";

            // When
            String result = circuitBreaker.execute(operation, fallback);

            // Then
            assertThat(result).isEqualTo("success");
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);
        }

        @Test
        @DisplayName("Должен вызвать fallback при exception в operation")
        void shouldInvokeFallbackOnOperationException() {
            // Given
            Supplier<String> operation = () -> {
                throw new RuntimeException("Redis connection failed");
            };
            Supplier<String> fallback = () -> "fallback_value";

            // When
            String result = circuitBreaker.execute(operation, fallback);

            // Then
            assertThat(result).isEqualTo("fallback_value");
            assertThat(circuitBreaker.getFailureCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Должен вызвать fallback без попытки operation в OPEN состоянии")
        void shouldInvokeFallbackWithoutTryingOperationWhenOpen() {
            // Given: Circuit Breaker открыт
            openCircuitBreaker();
            AtomicInteger operationCallCount = new AtomicInteger(0);
            AtomicInteger fallbackCallCount = new AtomicInteger(0);

            Supplier<String> operation = () -> {
                operationCallCount.incrementAndGet();
                return "operation";
            };
            Supplier<String> fallback = () -> {
                fallbackCallCount.incrementAndGet();
                return "fallback";
            };

            // When
            String result = circuitBreaker.execute(operation, fallback);

            // Then
            assertThat(result).isEqualTo("fallback");
            assertThat(operationCallCount.get()).isZero(); // operation НЕ вызвана
            assertThat(fallbackCallCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("Должен попытаться выполнить operation в HALF_OPEN состоянии")
        void shouldTryOperationInHalfOpenState() {
            // Given: Circuit Breaker в HALF_OPEN
            halfOpenCircuitBreaker();
            AtomicInteger operationCallCount = new AtomicInteger(0);

            Supplier<String> operation = () -> {
                operationCallCount.incrementAndGet();
                return "success";
            };
            Supplier<String> fallback = () -> "fallback";

            // When
            String result = circuitBreaker.execute(operation, fallback);

            // Then
            assertThat(result).isEqualTo("success");
            assertThat(operationCallCount.get()).isEqualTo(1); // operation была вызвана
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);
        }

        @Test
        @DisplayName("Должен корректно обрабатывать множественные failures подряд")
        void shouldHandleConsecutiveFailures() {
            // Given
            Supplier<String> failingOperation = () -> {
                throw new RuntimeException("Simulated failure");
            };
            Supplier<String> fallback = () -> "fallback";

            // When: Вызываем до достижения threshold
            for (int i = 0; i < TEST_FAILURE_THRESHOLD; i++) {
                String result = circuitBreaker.execute(failingOperation, fallback);
                assertThat(result).isEqualTo("fallback");
            }

            // Then: Circuit Breaker открыт
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.OPEN);
            assertThat(circuitBreaker.getFailureCount()).isEqualTo(TEST_FAILURE_THRESHOLD);
        }
    }

    @Nested
    @DisplayName("4. Thread Safety and Concurrency")
    @Order(4)
    class ConcurrencyTests {

        @Test
        @DisplayName("Должен корректно обрабатывать concurrent recordFailure вызовы")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldHandleConcurrentRecordFailureCalls() throws InterruptedException {
            // Given
            int threadCount = 10;
            int callsPerThread = 5;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);

            // When: Множественные потоки записывают failures одновременно
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await(); // Ждём сигнала старта
                        for (int j = 0; j < callsPerThread; j++) {
                            circuitBreaker.recordFailure();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown(); // Запускаем все потоки одновременно
            boolean completed = endLatch.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            // Then
            assertThat(completed).isTrue();
            assertThat(circuitBreaker.getFailureCount()).isEqualTo(threadCount * callsPerThread);
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.OPEN);
        }

        @Test
        @DisplayName("Должен корректно обрабатывать concurrent allowRequest вызовы")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldHandleConcurrentAllowRequestCalls() throws InterruptedException {
            // Given
            int threadCount = 20;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            AtomicInteger allowedCount = new AtomicInteger(0);

            // When: Множественные потоки проверяют allowRequest одновременно
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        if (circuitBreaker.allowRequest()) {
                            allowedCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = endLatch.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            // Then: Все запросы разрешены в CLOSED состоянии
            assertThat(completed).isTrue();
            assertThat(allowedCount.get()).isEqualTo(threadCount);
        }

        @Test
        @DisplayName("Должен безопасно переходить из OPEN в HALF_OPEN при конкурентных вызовах")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldSafelyTransitionFromOpenToHalfOpenConcurrently() throws InterruptedException {
            // Given: Circuit Breaker открыт
            openCircuitBreaker();
            testClock.advance(TEST_TIMEOUT.plusSeconds(1)); // Переходим за timeout

            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            AtomicInteger halfOpenTransitions = new AtomicInteger(0);

            // When: Множественные потоки пытаются перейти в HALF_OPEN
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        if (circuitBreaker.allowRequest()) {
                            halfOpenTransitions.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = endLatch.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            // Then: Только один поток должен выполнить переход (CAS operation)
            assertThat(completed).isTrue();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.HALF_OPEN);
        }
    }

    @Nested
    @DisplayName("5. Edge Cases and Special Scenarios")
    @Order(5)
    class EdgeCaseTests {

        @Test
        @DisplayName("Должен обрабатывать точное попадание в threshold")
        void shouldHandleExactThresholdMatch() {
            // Given & When: Записываем ровно failureThreshold failures
            for (int i = 0; i < TEST_FAILURE_THRESHOLD; i++) {
                circuitBreaker.recordFailure();
            }

            // Then
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.OPEN);
            assertThat(circuitBreaker.getFailureCount()).isEqualTo(TEST_FAILURE_THRESHOLD);
        }

        @Test
        @DisplayName("Должен обрабатывать превышение threshold")
        void shouldHandleThresholdExceedance() {
            // Given & When: Записываем больше failures чем threshold
            for (int i = 0; i < TEST_FAILURE_THRESHOLD + 10; i++) {
                circuitBreaker.recordFailure();
            }

            // Then: Circuit остаётся OPEN
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.OPEN);
            assertThat(circuitBreaker.getFailureCount()).isEqualTo(TEST_FAILURE_THRESHOLD + 10);
        }

        @Test
        @DisplayName("Должен обрабатывать transition ровно на границе timeout")
        void shouldHandleTransitionExactlyAtTimeoutBoundary() {
            // Given: Circuit OPEN
            openCircuitBreaker();
            long openTime = testClock.millis();

            // When: Переходим ровно на timeout
            testClock.setMillis(openTime + TEST_TIMEOUT.toMillis());

            // Then: НЕ переходим (нужно > timeout)
            assertThat(circuitBreaker.allowRequest()).isFalse();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.OPEN);

            // When: Переходим на timeout + 1ms
            testClock.setMillis(openTime + TEST_TIMEOUT.toMillis() + 1);

            // Then: Переходим в HALF_OPEN
            assertThat(circuitBreaker.allowRequest()).isTrue();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.HALF_OPEN);
        }

        @Test
        @DisplayName("Должен корректно работать с failureThreshold = 1")
        void shouldWorkWithMinimumThreshold() {
            // Given
            CircuitBreaker cbMin = CircuitBreaker.builder()
                    .failureThreshold(1)
                    .timeout(TEST_TIMEOUT)
                    .clock(testClock)
                    .build();

            // When: Один failure
            cbMin.recordFailure();

            // Then: Сразу переходим в OPEN
            assertThat(cbMin.getState()).isEqualTo(CircuitBreakerState.OPEN);
            assertThat(cbMin.getFailureCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Должен корректно работать с минимальным timeout")
        void shouldWorkWithMinimumTimeout() {
            // Given
            CircuitBreaker cbMinTimeout = CircuitBreaker.builder()
                    .failureThreshold(TEST_FAILURE_THRESHOLD)
                    .timeout(CircuitBreaker.MIN_TIMEOUT) // 100ms
                    .clock(testClock)
                    .build();

            // When: Открываем circuit
            for (int i = 0; i < TEST_FAILURE_THRESHOLD; i++) {
                cbMinTimeout.recordFailure();
            }
            assertThat(cbMinTimeout.getState()).isEqualTo(CircuitBreakerState.OPEN);

            // When: Ждём минимальный timeout + 1ms
            testClock.advance(CircuitBreaker.MIN_TIMEOUT.plusMillis(1));

            // Then: Переходим в HALF_OPEN
            assertThat(cbMinTimeout.allowRequest()).isTrue();
            assertThat(cbMinTimeout.getState()).isEqualTo(CircuitBreakerState.HALF_OPEN);
        }

        @Test
        @DisplayName("Должен обрабатывать чередование success и failure")
        void shouldHandleAlternatingSuccessAndFailure() {
            // Given & When: Чередуем success и failure
            for (int i = 0; i < 10; i++) {
                circuitBreaker.recordFailure();
                circuitBreaker.recordSuccess(); // Сбрасывает счётчик
            }

            // Then: Остаёмся в CLOSED (счётчик сбрасывается после каждого success)
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);
            assertThat(circuitBreaker.getFailureCount()).isZero();
        }

        @Test
        @DisplayName("Должен корректно обрабатывать recordFailureWithMetrics")
        void shouldHandleRecordFailureWithMetrics() {
            // Given
            Exception testException = new RuntimeException("Test exception");

            // When
            circuitBreaker.recordFailureWithMetrics(testException);

            // Then: Работает как обычный recordFailure
            assertThat(circuitBreaker.getFailureCount()).isEqualTo(1);
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);
        }

        @Test
        @DisplayName("Должен корректно записывать метрики через recordFailureWithMetrics")
        void shouldRecordMetricsOnFailure() {
            // Given: CircuitBreaker с моками для метрик
            MockMetrics mockMetrics = new MockMetrics();
            CircuitBreaker baseCircuitBreaker = CircuitBreaker.builder()
                    .failureThreshold(TEST_FAILURE_THRESHOLD)
                    .timeout(TEST_TIMEOUT)
                    .clock(testClock)
                    .build();

            ObservableCircuitBreaker observableCircuitBreaker = new ObservableCircuitBreaker(
                    baseCircuitBreaker,
                    mockMetrics,
                    "test-role"
            );

            Exception testException = new RuntimeException("Redis connection error");

            // When
            observableCircuitBreaker.recordFailureWithMetrics(testException);

            // Then: Метрики записаны
            assertThat(mockMetrics.recordedErrors).hasSize(1);
            assertThat(mockMetrics.recordedErrors.getFirst().role).isEqualTo("test-role");
            assertThat(mockMetrics.recordedErrors.getFirst().errorType).contains("infrastructure_error");

            // And: Circuit Breaker состояние обновлено (через delegate)
            assertThat(baseCircuitBreaker.getFailureCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("6. Realistic Scenarios")
    @Order(6)
    class RealisticScenarioTests {

        @Test
        @DisplayName("Должен обрабатывать полный цикл: CLOSED → OPEN → HALF_OPEN → CLOSED")
        void shouldHandleCompleteLifecycle() {
            // Phase 1: CLOSED → OPEN
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);

            for (int i = 0; i < TEST_FAILURE_THRESHOLD; i++) {
                circuitBreaker.recordFailure();
            }
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.OPEN);

            // Phase 2: OPEN → HALF_OPEN (после timeout)
            testClock.advance(TEST_TIMEOUT.plusSeconds(1));
            assertThat(circuitBreaker.allowRequest()).isTrue();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.HALF_OPEN);

            // Phase 3: HALF_OPEN → CLOSED (успех)
            circuitBreaker.recordSuccess();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);
            assertThat(circuitBreaker.getFailureCount()).isZero();
        }

        @Test
        @DisplayName("Должен обрабатывать failed recovery: HALF_OPEN → OPEN → HALF_OPEN → CLOSED")
        void shouldHandleFailedRecovery() {
            // Phase 1: CLOSED → OPEN
            openCircuitBreaker();

            // Phase 2: OPEN → HALF_OPEN
            testClock.advance(TEST_TIMEOUT.plusSeconds(1));
            circuitBreaker.allowRequest();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.HALF_OPEN);

            // Phase 3: HALF_OPEN → OPEN (failed recovery)
            circuitBreaker.recordFailure();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.OPEN);

            // Phase 4: OPEN → HALF_OPEN → CLOSED (successful recovery)
            testClock.advance(TEST_TIMEOUT.plusSeconds(1));
            circuitBreaker.allowRequest();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.HALF_OPEN);

            circuitBreaker.recordSuccess();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);
        }

        @Test
        @DisplayName("Должен обрабатывать множественные циклы открытия/закрытия")
        void shouldHandleMultipleOpenCloseCycles() {
            // Cycle 1
            openCircuitBreaker();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.OPEN);

            testClock.advance(TEST_TIMEOUT.plusSeconds(1));
            circuitBreaker.allowRequest();
            circuitBreaker.recordSuccess();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);

            // Cycle 2
            openCircuitBreaker();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.OPEN);

            testClock.advance(TEST_TIMEOUT.plusSeconds(1));
            circuitBreaker.allowRequest();
            circuitBreaker.recordSuccess();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);

            // Circuit остаётся стабильным
            assertThat(circuitBreaker.getFailureCount()).isZero();
        }

        @Test
        @DisplayName("Должен симулировать Redis недоступность и восстановление")
        void shouldSimulateRedisDowntimeAndRecovery() {
            // Given: Redis connection helper
            Supplier<String> redisOperation = () -> {
                // Симуляция Redis операции
                throw new RuntimeException("Redis connection refused");
            };
            Supplier<String> fallback = () -> "cached_value";

            // When: Redis недоступен - накапливаем failures
            for (int i = 0; i < TEST_FAILURE_THRESHOLD; i++) {
                String result = circuitBreaker.execute(redisOperation, fallback);
                assertThat(result).isEqualTo("cached_value");
            }

            // Then: Circuit открыт, операции не выполняются
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.OPEN);

            // When: Ждём timeout для recovery попытки
            testClock.advance(TEST_TIMEOUT.plusSeconds(1));

            // When: Redis восстановлен
            Supplier<String> workingOperation = () -> "fresh_value";
            String result = circuitBreaker.execute(workingOperation, fallback);

            // Then: Получаем свежие данные и circuit закрыт
            assertThat(result).isEqualTo("fresh_value");
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);
        }
    }

    private void openCircuitBreaker() {
        for (int i = 0; i < TEST_FAILURE_THRESHOLD; i++) {
            circuitBreaker.recordFailure();
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.OPEN);
    }

    private void halfOpenCircuitBreaker() {
        openCircuitBreaker();
        testClock.advance(TEST_TIMEOUT.plusSeconds(1));
        circuitBreaker.allowRequest(); // Triggers OPEN → HALF_OPEN transition
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.HALF_OPEN);
    }

    private static class TestClock extends Clock {
        private long currentMillis;

        public TestClock() {
            this.currentMillis = System.currentTimeMillis();
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.systemDefault();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(currentMillis);
        }

        @Override
        public long millis() {
            return currentMillis;
        }

        public void setMillis(long millis) {
            this.currentMillis = millis;
        }

        public void advance(Duration duration) {
            this.currentMillis += duration.toMillis();
        }
    }

    private static class MockMetrics implements space.br1440.platform.leaderelection.metrics.LeaderElectionMetricsInterface {
        static class RecordedError {
            String role;
            String errorType;
        }

        final java.util.List<RecordedError> recordedErrors = new java.util.ArrayList<>();

        @Override
        public void registerLeadershipGauge(String role, String podName) {
            // No-op
        }

        @Override
        public void updateLeadershipStatus(String role, int status) {
            // No-op
        }

        @Override
        public void recordLeadershipGranted(String leadershipName) {
            // No-op
        }

        @Override
        public void recordLeadershipRevoked(String leadershipName) {
            // No-op
        }

        @Override
        public void recordFailedAttempt(String role, String operationType) {
            RecordedError error = new RecordedError();
            error.role = role;
            error.errorType = operationType;
            recordedErrors.add(error);
        }

        @Override
        public void updateLeaseInfo(String role, String holderIdentity, long acquireTime, long renewTime, 
                                    int leaseDurationSeconds, int leaderTransitions) {
            // No-op
        }
    }

}
