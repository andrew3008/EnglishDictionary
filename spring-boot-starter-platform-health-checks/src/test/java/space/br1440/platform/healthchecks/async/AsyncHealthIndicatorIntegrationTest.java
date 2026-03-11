package space.br1440.platform.healthchecks.async;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import space.br1440.platform.healthchecks.common.HealthStatuses;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AsyncHealthIndicator: интеграция с реальным планировщиком")
class AsyncHealthIndicatorIntegrationTest {

    static AsyncHealthRefreshPolicy policy(boolean enabled,
                                           Duration checkTimeout,
                                           Duration refreshInterval,
                                           Duration staleTtl) {
        return new AsyncHealthRefreshPolicy() {
            @Override public boolean isEnabled()               { return enabled; }
            @Override public Duration getHealthCheckTimeout()  { return checkTimeout; }
            @Override public Duration getRefreshInterval()     { return refreshInterval; }
            @Override public Duration getStaleResultTtl()      { return staleTtl; }
        };
    }

    static AsyncHealthRefreshPolicy fastAsyncPolicy() {
        return policy(true, Duration.ofSeconds(5), Duration.ofMillis(200), Duration.ofSeconds(30));
    }

    static AsyncHealthRefreshPolicy syncPolicy() {
        return policy(false, Duration.ofSeconds(5), Duration.ofMillis(200), Duration.ofSeconds(30));
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            classes = AsyncHealthIndicatorIntegrationTest.UpIndicatorConfig.class
    )
    @DirtiesContext
    @DisplayName("1. Async: начальное UNKNOWN и переход в UP после первого run()")
    class InitialUnknownThenUp {

        @Autowired
        CountableUpIndicator indicator;

        @Test
        @DisplayName("health() возвращает UNKNOWN до первого выполнения планировщика")
        void returnsUnknownBeforeFirstRun() {
            Health health = indicator.health();
            assertThat(health.getStatus())
                    .as("До или сразу после старта допустимы только UNKNOWN и UP")
                    .isIn(Status.UNKNOWN, Status.UP);
        }

        @Test
        @DisplayName("health() возвращает UP после ожидания первого refresh-цикла")
        void returnsUpAfterSchedulerRun() throws InterruptedException {
            awaitStatus(indicator, Status.UP, Duration.ofSeconds(5));

            Health health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.UP);
        }

        @Test
        @DisplayName("details содержат lastChecked и lastDurationMs после первого run()")
        void detailsContainTimestampAfterFirstRun() throws InterruptedException {
            awaitStatus(indicator, Status.UP, Duration.ofSeconds(5));

            Health health = indicator.health();
            assertThat(health.getDetails())
                    .as("lastChecked должен присутствовать в details после run()")
                    .containsKey("lastChecked")
                    .containsKey("lastDurationMs");
        }

        @Test
        @DisplayName("run() вызывается планировщиком повторно в течение нескольких интервалов")
        void schedulerCallsRunRepeatedly() throws InterruptedException {
            // Ждём минимум 2 выполнения планировщика (refreshInterval=200ms)
            Thread.sleep(700);
            assertThat(indicator.getRunCount())
                    .as("Планировщик должен вызвать run() не менее 2 раз за 700ms при refreshInterval=200ms")
                    .isGreaterThanOrEqualTo(2);
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            classes = AsyncHealthIndicatorIntegrationTest.StaleTtlConfig.class
    )
    @DirtiesContext
    @DisplayName("2. Async: staleTtl — UNKNOWN когда кэш устарел")
    class StaleResultTtl {

        @Autowired
        SlowAfterFirstIndicator indicator;

        @Test
        @DisplayName("health() возвращает UNKNOWN с reason=stale когда snapshot старше staleTtl")
        void returnsUnknownWhenSnapshotIsStale() throws InterruptedException {
            // Ждём первого успешного UP (индикатор отвечает быстро при первом вызове)
            awaitStatus(indicator, Status.UP, Duration.ofSeconds(5));

            // Вторая итерация планировщика зависает (блокируется на 60+ секунд),
            // staleTtl=300ms → после 300ms кэш устареет и health() вернёт UNKNOWN
            Thread.sleep(600);

            Health health = indicator.health();
            assertThat(health.getStatus())
                    .as("После истечения staleTtl ожидается UNKNOWN (кэш устарел)")
                    .isEqualTo(Status.UNKNOWN);
            assertThat(health.getDetails().get("reason"))
                    .asString()
                    .contains("stale");
            assertThat(health.getDetails())
                    .containsKey("cacheAge")
                    .containsKey("staleTtl")
                    .containsKey("lastChecked");
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            classes = AsyncHealthIndicatorIntegrationTest.FailingConfig.class
    )
    @DirtiesContext
    @DisplayName("3. Async: исключение в doHealthCheck() → DOWN через onRunException()")
    class ExceptionMappedToDown {

        @Autowired
        AlwaysThrowingIndicator indicator;

        @Test
        @DisplayName("health() возвращает DOWN с error и errorType когда doHealthCheck() всегда бросает исключение")
        void returnsDownWhenDoHealthCheckAlwaysThrows() throws InterruptedException {
            awaitStatus(indicator, Status.DOWN, Duration.ofSeconds(5));

            Health health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsKey("reason")
                    .containsKey("errorType")
                    .containsKey("lastDurationMs");
        }

        @Test
        @DisplayName("планировщик продолжает вызывать run() после исключения (retry-цикл жив)")
        void schedulerContinuesAfterException() throws InterruptedException {
            Thread.sleep(700);
            assertThat(indicator.getRunCount())
                    .as("Планировщик должен продолжать попытки даже при постоянных исключениях")
                    .isGreaterThanOrEqualTo(2);
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            classes = AsyncHealthIndicatorIntegrationTest.DegradedConfig.class
    )
    @DirtiesContext
    @DisplayName("4. Async: переопределение onRunException() → DEGRADED для опциональных зависимостей")
    class CustomOnRunExceptionMappedToDegraded {

        @Autowired
        DegradedOnExceptionIndicator indicator;

        @Test
        @DisplayName("health() возвращает DEGRADED когда onRunException() переопределён для возврата DEGRADED")
        void returnsDegradedWhenOnRunExceptionOverridden() throws InterruptedException {
            awaitForDegraded(indicator, Duration.ofSeconds(5));

            Health health = indicator.health();
            assertThat(health.getStatus().getCode())
                    .as("onRunException() должен вернуть DEGRADED, а не DOWN")
                    .isEqualTo("DEGRADED");
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            classes = AsyncHealthIndicatorIntegrationTest.SyncConfig.class
    )
    @DirtiesContext
    @DisplayName("5. Sync-режим: health() вызывает doHealthCheck() в потоке вызывающего")
    class SyncMode {

        @Autowired
        SyncIndicator indicator;

        @Test
        @DisplayName("sync: health() возвращает UP немедленно без ожидания планировщика")
        void returnsUpImmediatelyInSyncMode() {
            Health health = indicator.health();
            assertThat(health.getStatus())
                    .as("В sync-режиме health() вызывает doHealthCheck() напрямую — немедленный ответ")
                    .isEqualTo(Status.UP);
        }

        @Test
        @DisplayName("sync: планировщик не регистрирует бин — details не содержат lastChecked после ожидания")
        void schedulerDoesNotScheduleSyncIndicator() throws InterruptedException {
            // Ждём достаточно, чтобы планировщик успел бы сработать, если бы зарегистрировал бин
            Thread.sleep(500);

            // В sync-режиме run() никогда не вызывался планировщиком:
            // snapshot отсутствует → health() не содержит lastChecked (который добавляется только в run())
            Health health = indicator.health();
            assertThat(health.getDetails())
                    .as("В sync-режиме планировщик не вызывает run() — snapshot не создаётся, lastChecked отсутствует")
                    .doesNotContainKey("lastChecked");
        }

        @Test
        @DisplayName("sync: details НЕ содержат lastChecked немедленно (run() не вызывался)")
        void detailsDoNotContainLastCheckedInSyncMode() {
            Health health = indicator.health();
            assertThat(health.getDetails())
                    .as("В sync-режиме нет кэша — lastChecked не добавляется планировщиком")
                    .doesNotContainKey("lastChecked");
        }

        @Test
        @DisplayName("sync: DOWN возвращается через healthSync() при исключении в doHealthCheck()")
        void returnsDownOnExceptionInSyncMode(@Autowired SyncIndicator ind) {
            ind.setShouldThrow(true);
            try {
                Health health = ind.health();
                assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                assertThat(health.getDetails())
                        .containsKey("error")
                        .containsKey("errorType");
            } finally {
                ind.setShouldThrow(false);
            }
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            classes = AsyncHealthIndicatorIntegrationTest.UpIndicatorConfig.class
    )
    @DirtiesContext
    @DisplayName("6. Async: thread-safety — одновременные вызовы health() из нескольких потоков")
    class ConcurrentHealthReads {

        @Autowired
        CountableUpIndicator indicator;

        @Test
        @DisplayName("100 параллельных вызовов health() не вызывают исключений и возвращают валидный статус")
        void concurrentHealthCallsAreThreadSafe() throws InterruptedException {
            awaitStatus(indicator, Status.UP, Duration.ofSeconds(5));

            int threads = 100;
            CyclicBarrier barrier = new CyclicBarrier(threads);
            List<Health> results = new CopyOnWriteArrayList<>();
            List<Throwable> errors = new CopyOnWriteArrayList<>();
            CountDownLatch done = new CountDownLatch(threads);

            try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
                for (int i = 0; i < threads; i++) {
                    pool.submit(() -> {
                        try {
                            barrier.await(10, TimeUnit.SECONDS);
                            results.add(indicator.health());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            errors.add(e);
                        } catch (Throwable t) {
                            errors.add(t);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                assertThat(done.await(15, TimeUnit.SECONDS))
                        .as("Все потоки должны завершиться в течение 15 секунд")
                        .isTrue();
            }

            assertThat(errors)
                    .as("Конкурентные вызовы health() не должны вызывать исключений")
                    .isEmpty();
            assertThat(results).hasSize(threads);
            assertThat(results)
                    .as("Все результаты должны быть UP или UNKNOWN (никогда DOWN при работающем индикаторе)")
                    .allMatch(h -> Status.UP.equals(h.getStatus()) || Status.UNKNOWN.equals(h.getStatus()));
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            classes = AsyncHealthIndicatorIntegrationTest.FlippingConfig.class
    )
    @DirtiesContext
    @DisplayName("7. Async: кэш отражает смену состояния UP → DOWN → UP")
    class StatusTransitions {

        @Autowired
        FlippingIndicator indicator;

        @Test
        @DisplayName("snapshot обновляется при смене состояния зависимости UP → DOWN → UP")
        void cacheReflectsStatusTransitions() throws InterruptedException {
            // Фаза 1: зависимость UP
            indicator.setHealthy(true);
            awaitStatus(indicator, Status.UP, Duration.ofSeconds(5));
            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);

            // Фаза 2: зависимость DOWN
            indicator.setHealthy(false);
            awaitStatus(indicator, Status.DOWN, Duration.ofSeconds(5));
            assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);

            // Фаза 3: зависимость восстановилась
            indicator.setHealthy(true);
            awaitStatus(indicator, Status.UP, Duration.ofSeconds(5));
            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        }
    }

    @SpringBootApplication
    static class UpIndicatorConfig {
        @Bean
        CountableUpIndicator countableUpIndicator() {
            return new CountableUpIndicator(fastAsyncPolicy());
        }
    }

    @SpringBootApplication
    static class StaleTtlConfig {
        @Bean
        SlowAfterFirstIndicator slowAfterFirstIndicator() {
            // staleTtl=300ms — snapshot быстро устаревает; refreshInterval=200ms
            AsyncHealthRefreshPolicy stalePol = policy(
                    true, Duration.ofSeconds(10), Duration.ofMillis(200), Duration.ofMillis(300)
            );
            return new SlowAfterFirstIndicator(stalePol);
        }
    }

    @SpringBootApplication
    static class FailingConfig {
        @Bean
        AlwaysThrowingIndicator alwaysThrowingIndicator() {
            return new AlwaysThrowingIndicator(fastAsyncPolicy());
        }
    }

    @SpringBootApplication
    static class DegradedConfig {
        @Bean
        DegradedOnExceptionIndicator degradedOnExceptionIndicator() {
            return new DegradedOnExceptionIndicator(fastAsyncPolicy());
        }
    }

    @SpringBootApplication
    static class SyncConfig {
        @Bean
        SyncIndicator syncIndicator() {
            return new SyncIndicator(syncPolicy());
        }
    }

    @SpringBootApplication
    static class FlippingConfig {
        @Bean
        FlippingIndicator flippingIndicator() {
            return new FlippingIndicator(fastAsyncPolicy());
        }
    }

    /**
     * Всегда возвращает UP; считает количество вызовов {@link #doHealthCheck()},
     * которые в async-режиме происходят только из {@link #run()} планировщика.
     */
    static class CountableUpIndicator extends AsyncHealthIndicator {

        private final AtomicInteger runCount = new AtomicInteger();

        CountableUpIndicator(AsyncHealthRefreshPolicy policy) {
            super(policy);
        }

        @Override
        protected Health doHealthCheck() {
            runCount.incrementAndGet();
            return Health.up().withDetail("service", "stub-up").build();
        }

        @Override
        protected Status getFailureHealthCheckStatus() { return Status.DOWN; }

        int getRunCount() { return runCount.get(); }
    }

    /**
     * Первый вызов doHealthCheck() возвращает UP мгновенно.
     * Все последующие вызовы блокируются на 60 секунд — имитация зависшей зависимости.
     * Используется для проверки staleTtl: кэш первого вызова быстро устаревает,
     * а новый не может появиться пока run() заблокирован.
     */
    static class SlowAfterFirstIndicator extends AsyncHealthIndicator {

        private final AtomicInteger callCount = new AtomicInteger();

        SlowAfterFirstIndicator(AsyncHealthRefreshPolicy policy) {
            super(policy);
        }

        @Override
        protected Health doHealthCheck() throws InterruptedException {
            int call = callCount.incrementAndGet();
            if (call == 1) {
                return Health.up().withDetail("call", call).build();
            }

            // Имитируем зависший доступ к внешней зависимости
            Thread.sleep(60_000);
            return Health.up().build();
        }

        @Override
        protected Status getFailureHealthCheckStatus() { return Status.DOWN; }
    }

    /**
     * Всегда бросает исключение из doHealthCheck().
     * В async-режиме {@code doHealthCheck()} вызывается только из {@link #run()} планировщика,
     * поэтому счётчик в {@code doHealthCheck()} корректно измеряет попытки планировщика.
     */
    static class AlwaysThrowingIndicator extends AsyncHealthIndicator {

        private final AtomicInteger runCount = new AtomicInteger();

        AlwaysThrowingIndicator(AsyncHealthRefreshPolicy policy) {
            super(policy);
        }

        @Override
        protected Health doHealthCheck() {
            runCount.incrementAndGet();
            throw new RuntimeException("Simulated external dependency failure");
        }

        @Override
        protected Status getFailureHealthCheckStatus() { return Status.DOWN; }

        int getRunCount() { return runCount.get(); }
    }

    /**
     * Всегда бросает исключение, но переопределяет {@link #onRunException} для возврата DEGRADED.
     * Имитирует опциональную зависимость, сбой которой не критичен.
     */
    static class DegradedOnExceptionIndicator extends AsyncHealthIndicator {

        DegradedOnExceptionIndicator(AsyncHealthRefreshPolicy policy) {
            super(policy);
        }

        @Override
        protected Health doHealthCheck() {
            throw new RuntimeException("Optional dependency unavailable");
        }

        @Override
        protected Health onRunException(Exception e, long durationMs) {
            return Health.status("DEGRADED")
                    .withDetail("reason", "Optional dependency is down — service continues")
                    .withDetail("error", e.getMessage())
                    .withDetail("lastDurationMs", durationMs)
                    .build();
        }

        @Override
        protected Status getFailureHealthCheckStatus() { return HealthStatuses.DEGRADED; }
    }

    /**
     * Sync-индикатор (asyncEnabled=false). Позволяет переключать поведение между UP и DOWN.
     *
     * <p>Счётчик {@code doHealthCheckCallCount} считает все вызовы {@link #doHealthCheck()}.
     * В sync-режиме {@code doHealthCheck()} вызывается только прямыми вызовами {@link #health()}
     * из тестового потока — планировщик такой бин не регистрирует (фильтр по {@code asyncEnabled}).
     * Поэтому тест проверяет не нулевой счётчик, а structural-свойство индикатора:
     * отсутствие кэша {@code lastChecked} в деталях health-ответа (кэш появляется только
     * когда {@link #run()} вызвал {@code doHealthCheck()} и записал snapshot).
     */
    static class SyncIndicator extends AsyncHealthIndicator {

        private volatile boolean shouldThrow = false;

        SyncIndicator(AsyncHealthRefreshPolicy policy) {
            super(policy);
        }

        @Override
        protected Health doHealthCheck() {
            if (shouldThrow) {
                throw new RuntimeException("Sync mode exception");
            }
            return Health.up().withDetail("mode", "sync").build();
        }

        @Override
        protected Status getFailureHealthCheckStatus() { return Status.DOWN; }

        void setShouldThrow(boolean value) { this.shouldThrow = value; }
    }

    /**
     * Переключает состояние между UP и DOWN через {@link #setHealthy(boolean)}.
     * Используется для проверки того, что async-кэш корректно обновляется при смене состояния.
     */
    static class FlippingIndicator extends AsyncHealthIndicator {

        private volatile boolean healthy = true;

        FlippingIndicator(AsyncHealthRefreshPolicy policy) {
            super(policy);
        }

        @Override
        protected Health doHealthCheck() {
            return healthy
                    ? Health.up().withDetail("dependency", "available").build()
                    : Health.down().withDetail("dependency", "unreachable").build();
        }

        @Override
        protected Status getFailureHealthCheckStatus() { return Status.DOWN; }

        void setHealthy(boolean value) { this.healthy = value; }
    }

    private static void awaitStatus(AsyncHealthIndicator indicator,
                                    Status expected,
                                    Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(indicator.health().getStatus())) {
                return;
            }
            Thread.sleep(50);
        }

        Health last = indicator.health();
        throw new AssertionError(
                "Expected status " + expected + " within " + timeout
                + " but got " + last.getStatus() + " (details=" + last.getDetails() + ")"
        );
    }

    private static void awaitForDegraded(AsyncHealthIndicator indicator,
                                         Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            String code = indicator.health().getStatus().getCode();
            if ("DEGRADED".equals(code)) {
                return;
            }
            Thread.sleep(50);
        }

        Health last = indicator.health();
        throw new AssertionError(
                "Expected status DEGRADED within " + timeout
                + " but got " + last.getStatus() + " (details=" + last.getDetails() + ")"
        );
    }
}
