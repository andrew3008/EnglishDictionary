package space.br1440.platform.leaderelection.annotation.schedularlock;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import space.br1440.platform.leaderelection.testutil.extension.container.EmbeddedRedisServer;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(
        classes = {
                SchedulerLockTestConfig.class,
                ScheduledTaskTestFixture.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@Tag("integration")
@Tag("redis")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Интеграционные тесты распределенных блокировок с ShedLock API")
class SchedulerLockSpringBootTest {

    @Autowired
    private EmbeddedRedisServer embeddedRedis;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ScheduledTaskTestFixture scheduledTaskTestFixture;

    @BeforeEach
    void setUp() {
        verifyRedisIsHealthy();
    }

    @AfterEach
    void clean() {
        scheduledTaskTestFixture.reset();
    }

    @Test
    @Order(1)
    @DisplayName("Должен выполнить задачу с блокировкой и вызвать колбэки захвата/освобождения")
    void shouldExecuteTaskWithLockLifecycleCallbacks() {
        // Then
        await("Task execution")
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        assertThat(scheduledTaskTestFixture.getExecutionCount()).isEqualTo(1)
                );

        // Проверяем, что выполнилось ровно 1 раз (не больше)
        assertThat(scheduledTaskTestFixture.getExecutionCount())
                .as("Task should execute exactly once")
                .isEqualTo(1);

        // And: Колбэк захвата блокировки был вызван
        await("Lock acquired callback")
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    assertThat(scheduledTaskTestFixture.getLockAcquiredEvents())
                            .as("Lock acquired callback should be invoked for testTask")
                            .contains("testTask");
                });

        // And: Колбэк освобождения блокировки был вызван после завершения задачи
        await("Lock released callback")
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    assertThat(scheduledTaskTestFixture.getLockReleasedEvents())
                            .as("Lock released callback should be invoked for testTask")
                            .contains("testTask");
                });

        // And: Блокировка больше не удерживается в Redis
        assertLockIsNotHeld("testTask");
    }

    @Test
    @Order(2)
    @DisplayName("Должен соблюдать параметр lockAtLeastFor и предотвращать раннее освобождение")
    void shouldRespectLockAtLeastForParameter() {
        // When: Ждем, пока задача executeFastTaskWithMinimumLockDuration выполнится через планировщик
        long startTime = System.currentTimeMillis();
        await("Fast task execution")
                .atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    int count = scheduledTaskTestFixture.getFastTaskExecutionCount();
                    assertThat(count)
                            .as("Fast task should execute at least once")
                            .isGreaterThanOrEqualTo(1);
                });

        // Then: Проверяем, что блокировка существует в Redis сразу после выполнения задачи
        await("Lock should be held")
                .atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    Boolean exists = redisTemplate.hasKey("test:fastTask");
                    assertThat(exists)
                            .as("Lock 'fastTask' should be held in Redis")
                            .isTrue();
                });

        // And: Проверяем, что блокировка освобождается после lockAtLeastFor duration
        long lockAtLeastForSeconds = 3;
        await("Lock released after lockAtLeastFor")
                .pollDelay(lockAtLeastForSeconds, TimeUnit.SECONDS)
                .atMost(lockAtLeastForSeconds + 3, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    assertLockIsNotHeld("fastTask");

                    long elapsedTime = System.currentTimeMillis() - startTime;
                    assertThat(elapsedTime)
                            .as("Lock should be held for at least lockAtLeastFor duration")
                            .isGreaterThanOrEqualTo(lockAtLeastForSeconds * 1000);
                });
    }

    @Test
    @Order(3)
    @DisplayName("Должен автоматически освобождать блокировку после lockAtMostFor при ошибке задачи")
    void shouldAutoReleaseLocksAfterLockAtMostForOnFailure() {
        // Given: Задача, которая завершится с ошибкой в процессе выполнения
        String lockName = "failingTask";

        // Then: Колбэк освобождения блокировки все еще вызывается (очистка)
        await("Lock released on failure")
                .atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    assertThat(scheduledTaskTestFixture.getLockReleasedEvents())
                            .as("Lock should be released even on task failure")
                            .contains(lockName);
                });

        // And: Блокировка больше не удерживается в Redis
        assertLockIsNotHeld(lockName);
    }

    @Test
    @Order(4)
    @DisplayName("Должен поддерживать несколько разных блокировок без взаимного влияния")
    void shouldSupportMultipleIndependentLocks() {
        // When & Then: Обе задачи должны выполниться (разные блокировки)
        await("First task execution")
                .atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        assertThat(scheduledTaskTestFixture.getFirstTaskExecutionCount()).isGreaterThanOrEqualTo(1)
                );

        await("Second task execution")
                .atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        assertThat(scheduledTaskTestFixture.getSecondTaskExecutionCount()).isGreaterThanOrEqualTo(1)
                );

        // And: Обе задачи выполнились успешно
        assertThat(scheduledTaskTestFixture.getFirstTaskExecutionCount())
                .as("First task should execute")
                .isGreaterThanOrEqualTo(1);

        assertThat(scheduledTaskTestFixture.getSecondTaskExecutionCount())
                .as("Second task should execute")
                .isGreaterThanOrEqualTo(1);

        // And: Колбэки захвата блокировки вызваны для обеих блокировок
        await("Both locks acquired")
                .atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    assertThat(scheduledTaskTestFixture.getLockAcquiredEvents())
                            .as("Both locks should be acquired")
                            .contains("firstTask", "secondTask");
                });
    }

    private void verifyRedisIsHealthy() {
        boolean isHealthy = embeddedRedis.isRedisHealthy(redisTemplate);
        assertThat(isHealthy)
                .as("Redis should be available and functioning correctly")
                .isTrue();
    }

    private void assertLockIsNotHeld(String lockName) {
        await("Lock released in Redis")
                .atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    Boolean exists = redisTemplate.hasKey("test:" + lockName);
                    assertThat(exists)
                            .as("Lock '" + lockName + "' should not be held in Redis")
                            .isFalse();
                });
    }

}
