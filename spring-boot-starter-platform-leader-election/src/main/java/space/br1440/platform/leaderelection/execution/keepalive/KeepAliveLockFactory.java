package space.br1440.platform.leaderelection.execution.keepalive;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.DisposableBean;
import space.br1440.platform.leaderelection.lock.DistributedLock;
import space.br1440.platform.leaderelection.util.ThreadFactoryBuilder;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class KeepAliveLockFactory implements DisposableBean {

    private static final Duration MINIMAL_LOCK_AT_MOST_FOR = Duration.ofSeconds(30);

    private final ScheduledExecutorService extensionScheduler;

    public KeepAliveLockFactory() {
        this(createDefaultScheduler());
    }

    public KeepAliveLock wrap(DistributedLock delegate,
                              String lockKey,
                              Duration lockAtMostFor,
                              Duration keepAliveLockUntil) {
        validateParameters(lockAtMostFor, keepAliveLockUntil);
        return new KeepAliveLock(
                delegate,
                lockKey,
                lockAtMostFor,
                keepAliveLockUntil,
                extensionScheduler
        );
    }

    private void validateParameters(Duration lockAtMostFor, Duration keepAliveLockUntil) {
        if (lockAtMostFor.compareTo(MINIMAL_LOCK_AT_MOST_FOR) < 0) {
            throw new IllegalArgumentException(
                    String.format(
                            "Невозможно использовать keep-alive с lockAtMostFor короче %s. " +
                            "Получено: %s. Причина: при коротком lockAtMostFor блокировка может истечь " +
                            "до того, как мы успеем её продлить.",
                            MINIMAL_LOCK_AT_MOST_FOR, lockAtMostFor
                    )
            );
        }

        if (keepAliveLockUntil.compareTo(lockAtMostFor) <= 0) {
            throw new IllegalArgumentException(
                    String.format(
                            "keepAliveLockUntil (%s) должен быть больше lockAtMostFor (%s). " +
                            "Иначе keep-alive не имеет смысла.",
                            keepAliveLockUntil, lockAtMostFor
                    )
            );
        }
    }

    private static ScheduledExecutorService createDefaultScheduler() {
        int poolSize = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                poolSize,
                new ThreadFactoryBuilder()
                        .setNameFormat("lock-extension-%d")
                        .setDaemon(true)
                        .build()
        );

        executor.setRemoveOnCancelPolicy(true);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    @Override
    public void destroy() {
        extensionScheduler.shutdown();
        try {
            if (!extensionScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                extensionScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            extensionScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

}

