package space.br1440.platform.datasource.postgres.internal.integration.health;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.datasource.postgres.api.properties.ReadMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingHealthProbeCoordinatorConcurrencyTest {

    private RoutingHealthProbeCoordinator coordinator;

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.destroy();
        }
    }

    @Test
    void fiftyConcurrentCallersExecuteExactlyOneWriteAndOneReadTask() throws Exception {
        AtomicInteger writeCalls = new AtomicInteger();
        AtomicInteger readCalls = new AtomicInteger();
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CyclicBarrier start = new CyclicBarrier(51);

        coordinator = new RoutingHealthProbeCoordinator();
        ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<ProbeResults>> futures = new ArrayList<>();

        for (int i = 0; i < 50; i++) {
            futures.add(callers.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return coordinator.probe(
                        Duration.ofSeconds(5),
                        ReadMode.SECONDARY_PREFERRED,
                        () -> {
                            writeCalls.incrementAndGet();
                            writeStarted.countDown();
                            try {
                                release.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return HealthCheckResult.success("primary");
                        },
                        () -> {
                            readCalls.incrementAndGet();
                            return HealthCheckResult.success("standby");
                        });
            }));
        }

        start.await(5, TimeUnit.SECONDS);
        assertThat(writeStarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(writeCalls.get()).isEqualTo(1);
        assertThat(readCalls.get()).isEqualTo(1);

        release.countDown();

        ProbeResults canonical = futures.getFirst().get(10, TimeUnit.SECONDS);
        for (Future<ProbeResults> future : futures) {
            assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(canonical);
        }

        callers.shutdownNow();
    }

    @Test
    void deadlineCompletesResponseWithoutClearingInFlightProbe() throws Exception {
        TrackingInlineExecutor executor = new TrackingInlineExecutor();
        coordinator = new RoutingHealthProbeCoordinator(executor, Duration.ofMillis(100), Duration.ofMillis(100));

        CountDownLatch writeEntered = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);

        Thread first = new Thread(() -> coordinator.probe(
                Duration.ofMillis(80),
                ReadMode.SECONDARY_PREFERRED,
                () -> {
                    writeEntered.countDown();
                    try {
                        releaseWrite.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return HealthCheckResult.success("primary");
                },
                () -> HealthCheckResult.success("standby")));
        first.start();

        writeEntered.await(2, TimeUnit.SECONDS);
        int tasksBeforeJoin = executor.submitted.get();

        ProbeResults timedOut = coordinator.probe(
                Duration.ofMillis(80),
                ReadMode.SECONDARY_PREFERRED,
                () -> HealthCheckResult.success("primary"),
                () -> HealthCheckResult.success("standby"));

        assertThat(timedOut.write().failureType()).isEqualTo(FailureType.TIMEOUT);
        assertThat(executor.submitted.get()).isEqualTo(tasksBeforeJoin);

        releaseWrite.countDown();
        first.join(5000);

        int tasksAfterDrain = executor.submitted.get();
        coordinator.probe(
                Duration.ofSeconds(1),
                ReadMode.SECONDARY_PREFERRED,
                () -> HealthCheckResult.success("primary"),
                () -> HealthCheckResult.success("standby"));
        assertThat(executor.submitted.get()).isGreaterThan(tasksAfterDrain);
    }

    @Test
    void canonicalCompletionRaceReturnsSharedResult() throws Exception {
        Phaser phaser = new Phaser(3);
        CountDownLatch writeReady = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<ProbeResults> first = new AtomicReference<>();
        AtomicReference<ProbeResults> second = new AtomicReference<>();

        coordinator = new RoutingHealthProbeCoordinator();

        Thread t1 = new Thread(() -> {
            phaser.arriveAndAwaitAdvance();
            first.set(coordinator.probe(
                    Duration.ofMillis(50),
                    ReadMode.SECONDARY_PREFERRED,
                    () -> {
                        writeReady.countDown();
                        try {
                            release.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return HealthCheckResult.success("primary");
                    },
                    () -> HealthCheckResult.success("standby")));
        });

        Thread t2 = new Thread(() -> {
            phaser.arriveAndAwaitAdvance();
            second.set(coordinator.probe(
                    Duration.ofMillis(50),
                    ReadMode.SECONDARY_PREFERRED,
                    () -> HealthCheckResult.success("primary"),
                    () -> HealthCheckResult.success("standby")));
        });

        t1.start();
        t2.start();
        phaser.arriveAndAwaitAdvance();
        writeReady.await(2, TimeUnit.SECONDS);
        release.countDown();
        t1.join(5000);
        t2.join(5000);

        assertThat(first.get()).isNotNull();
        assertThat(first.get()).isEqualTo(second.get());
    }

    @Test
    void deadlineBeforeSubmissionSkipsSuppliers() {
        coordinator = new RoutingHealthProbeCoordinator();
        AtomicInteger calls = new AtomicInteger();

        ProbeResults results = coordinator.probe(
                Duration.ZERO,
                ReadMode.SECONDARY_PREFERRED,
                () -> {
                    calls.incrementAndGet();
                    return HealthCheckResult.success("primary");
                },
                () -> {
                    calls.incrementAndGet();
                    return HealthCheckResult.success("standby");
                });

        assertThat(calls.get()).isZero();
        assertThat(results.write().failureType()).isEqualTo(FailureType.TIMEOUT);
        assertThat(results.read().failureType()).isEqualTo(FailureType.TIMEOUT);
    }

    static final class TrackingInlineExecutor extends java.util.concurrent.AbstractExecutorService {

        final AtomicInteger submitted = new AtomicInteger();

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
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            submitted.incrementAndGet();
            command.run();
        }
    }
}
