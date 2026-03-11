package space.br1440.platform.healthchecks.async;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.util.List;
import java.util.concurrent.*;

@Slf4j
public class SchedulingThreadPoolExecutor extends ThreadPoolExecutor {

    private static final long TERMINATION_TIMEOUT_SECONDS = 10;

    static class SchedulableFutureTask<V> extends FutureTask<V> {

        private final Schedulable schedulable;
        private volatile long startTimeMillis = -1L;

        public SchedulableFutureTask(Schedulable schedulable, V result) {
            super(schedulable, result);
            this.schedulable = schedulable;
        }

        void markStarted() {
            this.startTimeMillis = System.currentTimeMillis();
        }

        void clearStarted() {
            this.startTimeMillis = -1L;
        }

        long runningForMillis() {
            long st = startTimeMillis;
            return (st == -1L) ? -1L : (System.currentTimeMillis() - st);
        }
    }

    private final ConcurrentMap<SchedulableFutureTask<?>, Boolean> runningTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService coordinatorExecutorService;
    private final long hardTimeoutMs;

    public SchedulingThreadPoolExecutor(int poolSize, long keepAliveTimeInSeconds, long hardTimeoutMs) {
        super(poolSize, poolSize, keepAliveTimeInSeconds, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), createDaemonThreadFactory("async-health-"));

        this.hardTimeoutMs = hardTimeoutMs;
        this.coordinatorExecutorService = Executors.newSingleThreadScheduledExecutor(
                createDaemonThreadFactory("health-coordinator-")
        );

        startWatchdog();
    }

    private static CustomizableThreadFactory createDaemonThreadFactory(String prefix) {
        CustomizableThreadFactory factory = new CustomizableThreadFactory(prefix);
        factory.setDaemon(true);
        return factory;
    }

    private void startWatchdog() {
        long intervalMs = Math.max(hardTimeoutMs / 2, 1_000L);
        coordinatorExecutorService.scheduleAtFixedRate(() -> {
            try {
                cancelHungTasks();
            } catch (Exception e) {
                log.debug("Watchdog iteration failed", e);
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void cancelHungTasks() {
        for (SchedulableFutureTask<?> task : runningTasks.keySet()) {
            long runningMs = task.runningForMillis();
            if (runningMs > hardTimeoutMs) {
                log.warn("Cancelling hung health check task (running {}ms > {}ms hard timeout): {}",
                        runningMs, hardTimeoutMs, task.schedulable);
                task.cancel(true);
            }
        }
    }

    @Override
    protected void beforeExecute(Thread t, Runnable r) {
        super.beforeExecute(t, r);
        if (r instanceof SchedulableFutureTask<?> task) {
            task.markStarted();
            runningTasks.put(task, Boolean.TRUE);
        }
    }

    @Override
    public void shutdown() {
        super.shutdown();
        coordinatorExecutorService.shutdown();
    }

    @NotNull
    @Override
    public List<Runnable> shutdownNow() {
        coordinatorExecutorService.shutdownNow();
        return super.shutdownNow();
    }

    public void awaitGracefulTermination() {
        try {
            boolean workersTerminated = super.awaitTermination(TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            boolean coordinatorTerminated = coordinatorExecutorService.awaitTermination(TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!workersTerminated || !coordinatorTerminated) {
                log.warn("Graceful shutdown timed out (workers={}, coordinator={}), forcing shutdown",
                        workersTerminated ? "done" : "pending",
                        coordinatorTerminated ? "done" : "pending");
                shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdownNow();
        }
    }

    @Override
    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        if (runnable instanceof Schedulable schedulable) {
            return new SchedulableFutureTask<>(schedulable, value);
        }
        return super.newTaskFor(runnable, value);
    }

    @Override
    protected void afterExecute(Runnable runnable, Throwable throwable) {
        super.afterExecute(runnable, throwable);

        if (runnable instanceof SchedulableFutureTask<?> task) {
            task.clearStarted();
            runningTasks.remove(task);
        }

        extractAndLogException(runnable, throwable);

        if (isShutdown()) {
            return;
        }

        if (runnable instanceof SchedulableFutureTask<?> task) {
            try {
                coordinatorExecutorService.schedule(
                        () -> submit(task.schedulable),
                        task.schedulable.getRefreshInterval().toMillis(),
                        TimeUnit.MILLISECONDS
                );
            } catch (RejectedExecutionException e) {
                log.debug("Cannot reschedule, coordinator already shut down: {}", task.schedulable);
            }
        }
    }

    private void extractAndLogException(Runnable runnable, Throwable throwable) {
        Throwable t = throwable;
        if (t == null && runnable instanceof Future<?> future && future.isDone()) {
            try {
                future.get();
            } catch (CancellationException e) {
                if (isShutdown()) {
                    log.debug("Task cancelled during shutdown: {}", runnable);
                } else {
                    log.warn("Task cancelled (likely hard timeout): {}", runnable);
                }
                return;
            } catch (ExecutionException e) {
                t = e.getCause();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (t != null) {
            log.error("Uncaught exception in health check task: {}", runnable, t);
        }
    }

    public void schedule(Schedulable schedulable) {
        coordinatorExecutorService.submit(() -> submit(schedulable));
    }
}
