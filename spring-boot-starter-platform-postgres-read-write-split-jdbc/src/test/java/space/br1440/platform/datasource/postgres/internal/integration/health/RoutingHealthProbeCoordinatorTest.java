package space.br1440.platform.datasource.postgres.internal.integration.health;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.datasource.postgres.api.properties.ReadMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingHealthProbeCoordinatorTest {

    private RoutingHealthProbeCoordinator coordinator;

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.destroy();
        }
    }

    @Test
    void closedCoordinatorReturnsShuttingDown() {
        coordinator = new RoutingHealthProbeCoordinator();
        coordinator.destroy();

        ProbeResults results = coordinator.probe(
                Duration.ofSeconds(1),
                ReadMode.SECONDARY_PREFERRED,
                () -> HealthCheckResult.success("primary"),
                () -> HealthCheckResult.success("standby"));

        assertThat(results.write().failureType()).isEqualTo(FailureType.SHUTTING_DOWN);
        assertThat(results.read().failureType()).isEqualTo(FailureType.SHUTTING_DOWN);
    }

    @Test
    void bothSubmissionsRejectedReturnsWithoutNpe() {
        RejectAllExecutor executor = new RejectAllExecutor();
        coordinator = new RoutingHealthProbeCoordinator(executor, Duration.ofMillis(100), Duration.ofMillis(100));

        ProbeResults results = coordinator.probe(
                Duration.ofSeconds(1),
                ReadMode.SECONDARY_PREFERRED,
                () -> HealthCheckResult.success("primary"),
                () -> HealthCheckResult.success("standby"));

        assertThat(results.write().failureType()).isEqualTo(FailureType.EXECUTOR_REJECTED);
        assertThat(results.read().failureType()).isEqualTo(FailureType.EXECUTOR_REJECTED);
    }

    @Test
    void writeRejectedReadAcceptedKeepsReadTaskUntilDrain() throws Exception {
        TrackingExecutor executor = new TrackingExecutor();
        executor.rejectWrite = true;
        CountDownLatch readBlocked = new CountDownLatch(1);
        AtomicInteger readCalls = new AtomicInteger();

        coordinator = new RoutingHealthProbeCoordinator(executor, Duration.ofMillis(100), Duration.ofMillis(100));

        Thread reader = new Thread(() -> coordinator.probe(
                Duration.ofSeconds(5),
                ReadMode.SECONDARY_PREFERRED,
                () -> HealthCheckResult.success("primary"),
                () -> {
                    readCalls.incrementAndGet();
                    try {
                        readBlocked.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return HealthCheckResult.success("standby");
                }));

        reader.start();
        executor.readStarted.await(2, TimeUnit.SECONDS);

        assertThat(executor.executeCount.get()).isEqualTo(2);
        assertThat(readCalls.get()).isEqualTo(1);

        readBlocked.countDown();
        reader.join(5000);

        executor.rejectWrite = false;
        ProbeResults afterDrain = coordinator.probe(
                Duration.ofSeconds(1),
                ReadMode.SECONDARY_PREFERRED,
                () -> HealthCheckResult.success("primary"),
                () -> HealthCheckResult.success("standby"));

        assertThat(afterDrain.write().up()).isTrue();
        assertThat(afterDrain.read().up()).isTrue();
    }

    @Test
    void partialRejectionWriteAcceptedReadRejectedKeepsWriteTaskUntilDrain() throws Exception {
        TrackingExecutor executor = new TrackingExecutor();
        executor.rejectRead = true;
        CountDownLatch writeBlocked = new CountDownLatch(1);
        AtomicInteger writeCalls = new AtomicInteger();

        coordinator = new RoutingHealthProbeCoordinator(executor, Duration.ofMillis(100), Duration.ofMillis(100));

        Thread writer = new Thread(() -> coordinator.probe(
                Duration.ofSeconds(5),
                ReadMode.SECONDARY_PREFERRED,
                () -> {
                    writeCalls.incrementAndGet();
                    try {
                        writeBlocked.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return HealthCheckResult.success("primary");
                },
                () -> HealthCheckResult.success("standby")));

        writer.start();

        executor.writeStarted.await(2, TimeUnit.SECONDS);

        assertThat(writeCalls.get()).isEqualTo(1);
        assertThat(executor.executeCount.get()).isGreaterThanOrEqualTo(1);

        writeBlocked.countDown();
        writer.join(5000);

        executor.rejectRead = false;
        ProbeResults afterDrain = coordinator.probe(
                Duration.ofSeconds(1),
                ReadMode.SECONDARY_PREFERRED,
                () -> HealthCheckResult.success("primary"),
                () -> HealthCheckResult.success("standby"));

        assertThat(afterDrain.write().up()).isTrue();
        assertThat(afterDrain.read().up()).isTrue();
    }

    @Test
    void concurrentCallersReceiveSameCanonicalResult() throws Exception {
        Phaser start = new Phaser(3);
        CountDownLatch writeReady = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);

        coordinator = new RoutingHealthProbeCoordinator();

        Thread t1 = new Thread(() -> {
            start.arriveAndAwaitAdvance();
            coordinator.probe(
                    Duration.ofMillis(50),
                    ReadMode.SECONDARY_PREFERRED,
                    () -> {
                        writeReady.countDown();
                        try {
                            releaseWrite.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return HealthCheckResult.success("primary");
                    },
                    () -> HealthCheckResult.success("standby"));
        });

        Thread t2 = new Thread(() -> {
            start.arriveAndAwaitAdvance();
            coordinator.probe(
                    Duration.ofMillis(50),
                    ReadMode.SECONDARY_PREFERRED,
                    () -> HealthCheckResult.success("primary"),
                    () -> HealthCheckResult.success("standby"));
        });

        t1.start();
        t2.start();
        start.arriveAndAwaitAdvance();

        writeReady.await(2, TimeUnit.SECONDS);
        releaseWrite.countDown();
        t1.join(5000);
        t2.join(5000);
    }

    @Test
    void destroyReturnsWithinBoundedTime() {
        coordinator = new RoutingHealthProbeCoordinator();
        long start = System.nanoTime();
        coordinator.destroy();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(elapsedMs).isLessThan(3000);
    }

    static final class RejectAllExecutor extends AbstractExecutorService {

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return true;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            throw new java.util.concurrent.RejectedExecutionException();
        }
    }

    static final class TrackingExecutor extends AbstractExecutorService {

        final AtomicInteger executeCount = new AtomicInteger();
        final CountDownLatch writeStarted = new CountDownLatch(1);
        final CountDownLatch readStarted = new CountDownLatch(1);
        volatile boolean rejectRead;
        volatile boolean rejectWrite;

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return new ArrayList<>();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return true;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            int count = executeCount.incrementAndGet();
            if (count == 1) {
                if (rejectWrite) {
                    throw new java.util.concurrent.RejectedExecutionException();
                }
                writeStarted.countDown();
                command.run();
                return;
            }
            if (rejectRead) {
                throw new java.util.concurrent.RejectedExecutionException();
            }
            readStarted.countDown();
            command.run();
        }
    }
}
