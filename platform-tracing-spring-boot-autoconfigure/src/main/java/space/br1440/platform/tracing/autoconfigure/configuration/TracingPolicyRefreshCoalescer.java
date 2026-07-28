package space.br1440.platform.tracing.autoconfigure.configuration;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;

final class TracingPolicyRefreshCoalescer {

    private final Executor executor;
    private final LongConsumer refreshWorker;
    private final BiConsumer<Long, RuntimeException> failureHandler;
    private final AtomicLong requestedGeneration = new AtomicLong();
    private final AtomicLong completedGeneration = new AtomicLong();
    private final AtomicBoolean workerScheduled = new AtomicBoolean();

    TracingPolicyRefreshCoalescer(
            Executor executor,
            LongConsumer refreshWorker,
            BiConsumer<Long, RuntimeException> failureHandler) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.refreshWorker = Objects.requireNonNull(refreshWorker, "refreshWorker");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    long signalRefresh() {
        long generation = requestedGeneration.incrementAndGet();
        scheduleWorker();
        return generation;
    }

    long requestedGeneration() {
        return requestedGeneration.get();
    }

    long completedGeneration() {
        return completedGeneration.get();
    }

    boolean workerScheduled() {
        return workerScheduled.get();
    }

    private void scheduleWorker() {
        if (workerScheduled.compareAndSet(false, true)) {
            try {
                executor.execute(this::drain);
            } catch (RuntimeException exception) {
                workerScheduled.set(false);
                throw exception;
            }
        }
    }

    private void drain() {
        while (true) {
            long generation = requestedGeneration.get();
            try {
                refreshWorker.accept(generation);
            } catch (RuntimeException exception) {
                failureHandler.accept(generation, exception);
            } finally {
                completedGeneration.accumulateAndGet(generation, Math::max);
            }

            if (requestedGeneration.get() != generation) {
                continue;
            }
            workerScheduled.set(false);
            if (requestedGeneration.get() == generation
                    || !workerScheduled.compareAndSet(false, true)) {
                return;
            }
        }
    }
}
