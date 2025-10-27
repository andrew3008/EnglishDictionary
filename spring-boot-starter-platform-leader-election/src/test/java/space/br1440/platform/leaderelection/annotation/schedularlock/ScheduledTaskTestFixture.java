package space.br1440.platform.leaderelection.annotation.schedularlock;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import space.br1440.platform.leaderelection.annotation.locking.OnLockAcquired;
import space.br1440.platform.leaderelection.annotation.locking.OnLockReleased;
import space.br1440.platform.leaderelection.annotation.locking.SchedulerLock;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@EnableScheduling
class ScheduledTaskTestFixture {

    private final AtomicInteger executionCount = new AtomicInteger(0);
    private final AtomicBoolean taskExecuted = new AtomicBoolean(false);
    private final AtomicInteger fastTaskExecutionCount = new AtomicInteger(0);
    private final AtomicInteger firstTaskExecutionCount = new AtomicInteger(0);
    private final AtomicInteger secondTaskExecutionCount = new AtomicInteger(0);
    private final CopyOnWriteArrayList<String> lockAcquiredEvents = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> lockReleasedEvents = new CopyOnWriteArrayList<>();

    @Scheduled(fixedDelay = 1000)
    @SchedulerLock(
            lockName = "testTask",
            lockAtMostFor = "30s",
            lockAtLeastFor = "1s"
    )
    public void executeTaskWithLock() {
        if (!taskExecuted.compareAndSet(false, true)) {
            return;
        }

        executionCount.incrementAndGet();
        try {
            Thread.sleep(100); // Имитируем работу
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Scheduled(fixedDelay = 2000)
    @SchedulerLock(
            lockName = "fastTask",
            lockAtMostFor = "10s",
            lockAtLeastFor = "3s"
    )
    public void executeFastTaskWithMinimumLockDuration() {
        fastTaskExecutionCount.incrementAndGet();
        // Быстрая задача, которая завершается за < lockAtLeastFor
    }

    @Scheduled(fixedDelay = 3000)
    @SchedulerLock(
            lockName = "failingTask",
            lockAtMostFor = "5s"
    )
    public void executeFailingTask() {
        throw new RuntimeException("Simulated task failure");
    }

    @Scheduled(fixedDelay = 4000)
    @SchedulerLock(lockName = "firstTask", lockAtMostFor = "5s")
    public void executeFirstTask() {
        firstTaskExecutionCount.incrementAndGet();
    }

    @Scheduled(fixedDelay = 5000)
    @SchedulerLock(lockName = "secondTask", lockAtMostFor = "5s")
    public void executeSecondTask() {
        secondTaskExecutionCount.incrementAndGet();
    }

    /**
     * Колбэк, вызываемый при захвате блокировки.
     */
    @OnLockAcquired(lockName = "testTask")
    public void onTestTaskLockAcquired() {
        lockAcquiredEvents.add("testTask");
    }

    @OnLockAcquired(lockName = "firstTask")
    public void onFirstTaskLockAcquired() {
        lockAcquiredEvents.add("firstTask");
    }

    @OnLockAcquired(lockName = "secondTask")
    public void onSecondTaskLockAcquired() {
        lockAcquiredEvents.add("secondTask");
    }

    /**
     * Колбэк, вызываемый при освобождении блокировки.
     */
    @OnLockReleased(lockName = "testTask")
    public void onTestTaskLockReleased() {
        lockReleasedEvents.add("testTask");
    }

    @OnLockReleased(lockName = "firstTask")
    public void onFirstTaskLockReleased() {
        lockReleasedEvents.add("firstTask");
    }

    @OnLockReleased(lockName = "failingTask")
    public void onFailingTaskLockReleased() {
        lockReleasedEvents.add("failingTask");
    }

    @OnLockReleased(lockName = "secondTask")
    public void onSecondTaskLockReleased() {
        lockReleasedEvents.add("secondTask");
    }

    public void reset() {
        executionCount.set(0);
        fastTaskExecutionCount.set(0);
        firstTaskExecutionCount.set(0);
        secondTaskExecutionCount.set(0);
        taskExecuted.set(false);
        lockAcquiredEvents.clear();
        lockReleasedEvents.clear();
    }

    public int getExecutionCount() {
        return executionCount.get();
    }

    public int getFastTaskExecutionCount() {
        return fastTaskExecutionCount.get();
    }

    public int getFirstTaskExecutionCount() {
        return firstTaskExecutionCount.get();
    }

    public int getSecondTaskExecutionCount() {
        return secondTaskExecutionCount.get();
    }

    public CopyOnWriteArrayList<String> getLockAcquiredEvents() {
        return lockAcquiredEvents;
    }

    public CopyOnWriteArrayList<String> getLockReleasedEvents() {
        return lockReleasedEvents;
    }

}
