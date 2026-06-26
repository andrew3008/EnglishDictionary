package space.br1440.platform.datasource.postgres.internal.integration.health;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.br1440.platform.datasource.postgres.api.properties.ReadMode;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Slf4j
final class RoutingHealthProbeCoordinator {

    static final Duration SHUTDOWN_GRACE_PERIOD = Duration.ofSeconds(1);
    static final Duration SHUTDOWN_FORCE_PERIOD = Duration.ofSeconds(1);

    private final ExecutorService executor;
    private final Duration shutdownGracePeriod;
    private final Duration shutdownForcePeriod;
    private final Object monitor = new Object();

    private ProbeExecution current;
    private volatile boolean accepting = true;

    RoutingHealthProbeCoordinator() {
        this(Executors.newVirtualThreadPerTaskExecutor(), SHUTDOWN_GRACE_PERIOD, SHUTDOWN_FORCE_PERIOD);
    }

    RoutingHealthProbeCoordinator(ExecutorService executor,
                                  Duration shutdownGracePeriod,
                                  Duration shutdownForcePeriod) {
        this.executor = executor;
        this.shutdownGracePeriod = shutdownGracePeriod;
        this.shutdownForcePeriod = shutdownForcePeriod;
    }

    ProbeResults probe(Duration timeout,
                       ReadMode readMode,
                       Supplier<HealthCheckResult> writeCheck,
                       Supplier<HealthCheckResult> readCheck) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        ProbeExecution probe;
        synchronized (monitor) {
            if (!accepting) {
                return ProbeResults.failedBoth(FailureType.SHUTTING_DOWN, readMode);
            }

            probe = current;
            if (probe == null || probe.isDrained()) {
                if (System.nanoTime() >= deadlineNanos) {
                    return ProbeResults.failedBoth(FailureType.TIMEOUT, readMode);
                }
                probe = new ProbeExecution(deadlineNanos, readMode);
                current = probe;
                executeComponent(probe, probe.writeResult, writeCheck);
                executeComponent(probe, probe.readResult, readCheck);
            }
        }

        return awaitResults(probe);
    }

    void destroy() {
        synchronized (monitor) {
            accepting = false;
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownGracePeriod.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(shutdownForcePeriod.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.warn("Routing health executor did not terminate after bounded shutdown");
                }
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private ProbeResults awaitResults(ProbeExecution probe) {
        try {
            long remainingNanos = probe.deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return probe.completeFromAvailableResults();
            }

            return probe.response.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            return probe.completeFromAvailableResults();
        } catch (ExecutionException ex) {
            log.warn("Routing health probe internal failure");
            return ProbeResults.failedBoth(FailureType.INTERNAL_ERROR, probe.readMode);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ProbeInterruptedException();
        }
    }

    private void executeComponent(ProbeExecution probe,
                                  AtomicReference<HealthCheckResult> slot,
                                  Supplier<HealthCheckResult> check) {
        if (System.nanoTime() >= probe.deadlineNanos) {
            slot.compareAndSet(null, HealthCheckResult.failed(FailureType.TIMEOUT));
            componentFinished(probe);
            return;
        }

        try {
            executor.execute(() -> runCheck(probe, slot, check));
        } catch (RejectedExecutionException ex) {
            log.warn("Routing health executor rejected component submission");
            slot.compareAndSet(null, HealthCheckResult.failed(FailureType.EXECUTOR_REJECTED));
            componentFinished(probe);
        }
    }

    private void runCheck(ProbeExecution probe,
                          AtomicReference<HealthCheckResult> slot,
                          Supplier<HealthCheckResult> check) {
        try {
            if (System.nanoTime() >= probe.deadlineNanos) {
                slot.compareAndSet(null, HealthCheckResult.failed(FailureType.TIMEOUT));
                return;
            }

            slot.set(check.get());
        } catch (Exception ex) {
            log.debug("Routing JDBC health check failed", ex);
            slot.compareAndSet(null, HealthCheckResult.failed(FailureType.INTERNAL_ERROR));
        } finally {
            slot.compareAndSet(null, HealthCheckResult.failed(FailureType.INTERNAL_ERROR));
            componentFinished(probe);
        }
    }

    private void componentFinished(ProbeExecution probe) {
        if (probe.remaining.decrementAndGet() == 0) {
            probe.completeFromAvailableResults();
        }

        tryClear(probe);
    }

    private void tryClear(ProbeExecution probe) {
        synchronized (monitor) {
            if (current == probe && probe.isDrained()) {
                current = null;
            }
        }
    }

    static final class ProbeExecution {

        final CompletableFuture<ProbeResults> response = new CompletableFuture<>();
        final long deadlineNanos;
        final ReadMode readMode;
        final AtomicReference<HealthCheckResult> writeResult = new AtomicReference<>();
        final AtomicReference<HealthCheckResult> readResult = new AtomicReference<>();
        final AtomicInteger remaining = new AtomicInteger(2);

        ProbeExecution(long deadlineNanos, ReadMode readMode) {
            this.deadlineNanos = deadlineNanos;
            this.readMode = readMode;
        }

        boolean isDrained() {
            return response.isDone() && remaining.get() == 0;
        }

        ProbeResults snapshotAvailableResults() {
            HealthCheckResult write = writeResult.get();
            if (write == null) {
                write = HealthCheckResult.failed(FailureType.TIMEOUT);
            }

            HealthCheckResult read = readResult.get();
            if (read == null) {
                read = HealthCheckResult.failed(FailureType.TIMEOUT);
            }

            return new ProbeResults(write, read, readMode);
        }

        ProbeResults completeFromAvailableResults() {
            ProbeResults candidate = snapshotAvailableResults();
            if (response.complete(candidate)) {
                return candidate;
            }

            return response.join();
        }
    }
}
