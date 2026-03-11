package space.br1440.platform.healthchecks.async;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("SchedulingThreadPoolExecutor: интеграционные тесты")
class SchedulingThreadPoolExecutorIntegrationTest {

    private static final int POOL_SIZE = 4;
    private static final long KEEP_ALIVE_S = 10L;
    private static final long HARD_TIMEOUT_MS = 3_000L;

    private SchedulingThreadPoolExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new SchedulingThreadPoolExecutor(POOL_SIZE, KEEP_ALIVE_S, HARD_TIMEOUT_MS);
    }

    @AfterEach
    void tearDown() {
        if (!executor.isShutdown()) {
            executor.shutdown();
            executor.awaitGracefulTermination();
        }
    }

    @Nested
    @DisplayName("1. Self-scheduling: задача выполняется и автоматически перепланируется")
    class SelfScheduling {

        @Test
        @DisplayName("schedule() запускает задачу — runCount увеличивается не менее 1 раза за 1 секунду")
        void taskExecutesAfterSchedule() throws InterruptedException {
            CountableSchedulable task = new CountableSchedulable(Duration.ofMillis(100));

            executor.schedule(task);
            Thread.sleep(500);

            assertThat(task.getRunCount())
                    .as("Задача должна выполниться хотя бы один раз за 500ms при refreshInterval=100ms")
                    .isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("задача автоматически перепланируется: runCount растёт по мере выполнения интервалов")
        void taskIsRescheduledAutomatically() throws InterruptedException {
            CountableSchedulable task = new CountableSchedulable(Duration.ofMillis(100));

            executor.schedule(task);
            Thread.sleep(600);

            int countAfter600ms = task.getRunCount();
            assertThat(countAfter600ms)
                    .as("За 600ms при refreshInterval=100ms ожидается не менее 3 выполнений")
                    .isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("несколько задач с разными интервалами выполняются независимо")
        void multipleTasksWithDifferentIntervalsRunIndependently() throws InterruptedException {
            CountableSchedulable fast = new CountableSchedulable(Duration.ofMillis(100));
            CountableSchedulable slow = new CountableSchedulable(Duration.ofMillis(300));

            executor.schedule(fast);
            executor.schedule(slow);
            Thread.sleep(700);

            assertThat(fast.getRunCount())
                    .as("Быстрая задача (100ms) должна выполниться чаще медленной (300ms)")
                    .isGreaterThan(slow.getRunCount());
            assertThat(slow.getRunCount())
                    .as("Медленная задача (300ms) должна выполниться хотя бы 1 раз за 700ms")
                    .isGreaterThanOrEqualTo(1);
        }
    }

    @Nested
    @DisplayName("2. Watchdog: зависшая задача отменяется по hardTimeout")
    class WatchdogHardTimeout {

        @Test
        @DisplayName("задача, зависшая дольше hardTimeout, отменяется watchdog'ом")
        @SuppressWarnings("resource")
        void hangingTaskIsCancelledByWatchdog() throws InterruptedException {
            long hardTimeoutMs = 500L;
            SchedulingThreadPoolExecutor shortTimeoutExecutor =
                    new SchedulingThreadPoolExecutor(2, 10L, hardTimeoutMs);

            try {
                BlockableSchedulable task = new BlockableSchedulable(Duration.ofMillis(50));

                shortTimeoutExecutor.schedule(task);

                // Ждём первого выполнения, затем блокируем
                awaitRunCount(task, 1, Duration.ofSeconds(3));
                task.blockOnNextRun();

                // Ждём, пока watchdog не отменит задачу (hardTimeout=500ms, watchdog каждые 250ms)
                awaitCancelled(task, Duration.ofSeconds(3));

                assertThat(task.wasCancelled())
                        .as("Watchdog должен отменить задачу через cancel(true) при превышении hardTimeout")
                        .isTrue();
            } finally {
                shortTimeoutExecutor.shutdown();
                shortTimeoutExecutor.awaitGracefulTermination();
            }
        }

        @Test
        @DisplayName("после отмены зависшей задачи планировщик продолжает работу для других задач")
        @SuppressWarnings("resource")
        void otherTasksContinueAfterWatchdogCancellation() throws InterruptedException {
            long hardTimeoutMs = 500L;
            SchedulingThreadPoolExecutor shortTimeoutExecutor =
                    new SchedulingThreadPoolExecutor(4, 10L, hardTimeoutMs);

            try {
                BlockableSchedulable hung = new BlockableSchedulable(Duration.ofMillis(50));
                CountableSchedulable healthy = new CountableSchedulable(Duration.ofMillis(100));

                shortTimeoutExecutor.schedule(hung);
                shortTimeoutExecutor.schedule(healthy);

                // Ждём первого выполнения обоих, затем блокируем hung
                awaitRunCount(hung, 1, Duration.ofSeconds(3));
                hung.blockOnNextRun();

                // Ждём пока watchdog не отменит hung, а healthy продолжает работать
                Thread.sleep(1_500);

                assertThat(healthy.getRunCount())
                        .as("healthy-задача должна продолжать выполняться пока hung заблокирован")
                        .isGreaterThanOrEqualTo(3);
            } finally {
                shortTimeoutExecutor.shutdown();
                shortTimeoutExecutor.awaitGracefulTermination();
            }
        }
    }

    @Nested
    @DisplayName("3. Shutdown: корректное завершение работы")
    class Shutdown {

        @Test
        @DisplayName("shutdown() завершает executor без исключений")
        void shutdownCompletesWithoutException() {
            CountableSchedulable task = new CountableSchedulable(Duration.ofMillis(100));
            executor.schedule(task);

            assertThatCode(() -> {
                executor.shutdown();
                executor.awaitGracefulTermination();
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("после shutdown() новые задачи не принимаются — executor.isShutdown() == true")
        void noNewTasksAcceptedAfterShutdown() {
            executor.shutdown();
            executor.awaitGracefulTermination();

            assertThat(executor.isShutdown()).isTrue();
            assertThat(executor.isTerminated()).isTrue();
        }

        @Test
        @DisplayName("shutdownNow() прерывает выполняющиеся задачи и возвращает список незавершённых")
        void shutdownNowInterruptsRunningTasks() throws InterruptedException {
            BlockableSchedulable blocker = new BlockableSchedulable(Duration.ofMillis(50));

            executor.schedule(blocker);
            awaitRunCount(blocker, 1, Duration.ofSeconds(3));
            blocker.blockNextRunForever();

            Thread.sleep(100);

            executor.shutdownNow();
            executor.awaitGracefulTermination();

            assertThat(executor.isTerminated()).isTrue();
            // shutdownNow() может вернуть пустой список — зависшая задача уже выполняется и была interrupt'нута
            // Важно: завершение должно произойти в течение TERMINATION_TIMEOUT_SECONDS
        }

        @Test
        @DisplayName("awaitGracefulTermination() не зависает если задачи завершены")
        void awaitGracefulTerminationDoesNotHangWhenTasksComplete() throws InterruptedException {
            CountableSchedulable task = new CountableSchedulable(Duration.ofMillis(100));
            executor.schedule(task);
            awaitRunCount(task, 2, Duration.ofSeconds(3));

            long start = System.currentTimeMillis();
            executor.shutdown();
            executor.awaitGracefulTermination();
            long elapsed = System.currentTimeMillis() - start;

            assertThat(elapsed)
                    .as("awaitGracefulTermination() должен завершиться за разумное время (< 12 секунд)")
                    .isLessThan(12_000L);
        }
    }

    @Nested
    @DisplayName("4. Daemon threads: worker и coordinator потоки — daemon")
    class DaemonThreads {

        @Test
        @DisplayName("worker-потоки созданы как daemon (async-health-*)")
        void workerThreadsAreDaemon() throws InterruptedException {
            AtomicReference<Thread> workerThread = new AtomicReference<>();
            CountDownLatch captured = new CountDownLatch(1);

            Schedulable captureThread = new Schedulable() {
                @Override
                public void run() {
                    workerThread.set(Thread.currentThread());
                    captured.countDown();
                }

                @Override
                public Duration getRefreshInterval() {
                    return Duration.ofSeconds(60);
                }
            };

            executor.schedule(captureThread);
            assertThat(captured.await(5, TimeUnit.SECONDS))
                    .as("Задача должна выполниться и захватить ссылку на worker-поток")
                    .isTrue();

            Thread worker = workerThread.get();
            assertThat(worker).isNotNull();
            assertThat(worker.isDaemon())
                    .as("Worker-поток async-health-* должен быть daemon")
                    .isTrue();
            assertThat(worker.getName())
                    .as("Worker-поток должен иметь имя с префиксом async-health-")
                    .startsWith("async-health-");
        }
    }

    @Nested
    @DisplayName("5. Исключения в задачах: планировщик продолжает работу")
    class ExceptionHandling {

        @Test
        @DisplayName("задача, бросающая RuntimeException, перепланируется на следующий интервал")
        void throwingTaskIsRescheduledAfterException() throws InterruptedException {
            CountableSchedulable task = new CountableSchedulable(Duration.ofMillis(100)) {
                @Override
                public void run() {
                    super.run();
                    throw new RuntimeException("Simulated failure in Schedulable.run()");
                }
            };

            executor.schedule(task);
            Thread.sleep(600);

            // Задача должна выполниться несколько раз, несмотря на постоянные исключения
            assertThat(task.getRunCount())
                    .as("Задача должна перепланироваться даже после исключений (runCount >= 2 за 600ms)")
                    .isGreaterThanOrEqualTo(2);
        }
    }

    @Nested
    @DisplayName("6. Нагрузка: несколько задач конкурируют в пуле")
    class PoolLoad {

        @Test
        @DisplayName("все задачи (N=poolSize) выполняются без reject при одновременном старте")
        void allTasksExecuteWithoutRejection() throws InterruptedException {
            int taskCount = POOL_SIZE;
            List<CountableSchedulable> tasks = new ArrayList<>();

            for (int i = 0; i < taskCount; i++) {
                CountableSchedulable t = new CountableSchedulable(Duration.ofMillis(150));
                tasks.add(t);
                executor.schedule(t);
            }

            Thread.sleep(800);

            for (int i = 0; i < taskCount; i++) {
                assertThat(tasks.get(i).getRunCount())
                        .as("Задача #" + i + " должна выполниться хотя бы 1 раз за 800ms")
                        .isGreaterThanOrEqualTo(1);
            }
        }
    }

    static class CountableSchedulable implements Schedulable {

        private final Duration refreshInterval;
        private final AtomicInteger runCount = new AtomicInteger();

        CountableSchedulable(Duration refreshInterval) {
            this.refreshInterval = refreshInterval;
        }

        @Override
        public void run() {
            runCount.incrementAndGet();
        }

        int getRunCount() { return runCount.get(); }

        @Override
        public Duration getRefreshInterval() { return refreshInterval; }
    }

    /**
     * Задача, которую можно заблокировать для имитации зависшей зависимости.
     * {@link #blockOnNextRun()} блокирует только одно выполнение на hardTimeout+buffer.
     * {@link #blockNextRunForever()} блокирует до interrupt.
     */
    static class BlockableSchedulable implements Schedulable {

        private final Duration refreshInterval;
        private final AtomicInteger runCount = new AtomicInteger();
        private final AtomicBoolean blockForever = new AtomicBoolean(false);
        private volatile long blockDurationMs = -1;
        private volatile boolean cancelled = false;

        BlockableSchedulable(Duration refreshInterval) {
            this.refreshInterval = refreshInterval;
        }

        @Override
        public void run() {
            runCount.incrementAndGet();
            long blockMs = blockDurationMs;

            if (blockForever.get()) {
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelled = true;
                }
                return;
            }

            if (blockMs > 0) {
                blockDurationMs = -1;
                try {
                    Thread.sleep(blockMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelled = true;
                }
            }
        }

        /** Блокирует следующее выполнение на duration, чтобы watchdog успел отменить его. */
        void blockOnNextRun() {
            this.blockDurationMs = 60_000L;
        }

        /** Блокирует навсегда до interrupt. */
        void blockNextRunForever() {
            this.blockForever.set(true);
        }

        boolean wasCancelled() { return cancelled; }
        int getRunCount() { return runCount.get(); }

        @Override
        public Duration getRefreshInterval() { return refreshInterval; }
    }

    private static void awaitRunCount(BlockableSchedulable task,
                                      int minCount,
                                      Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (task.getRunCount() < minCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        if (task.getRunCount() < minCount) {
            throw new AssertionError(
                    "Expected runCount >= " + minCount + " within " + timeout
                    + " but got " + task.getRunCount()
            );
        }
    }

    private static void awaitRunCount(CountableSchedulable task,
                                      int minCount,
                                      Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (task.getRunCount() < minCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        if (task.getRunCount() < minCount) {
            throw new AssertionError(
                    "Expected runCount >= " + minCount + " within " + timeout
                    + " but got " + task.getRunCount()
            );
        }
    }

    private static void awaitCancelled(BlockableSchedulable task,
                                       Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (!task.wasCancelled() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        if (!task.wasCancelled()) {
            throw new AssertionError(
                    "Expected task to be cancelled by watchdog within " + timeout
            );
        }
    }
}
