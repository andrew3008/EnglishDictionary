package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;

/**
 * Один lifecycle-owned publisher для динамических JMX observation-снимков.
 */
public final class ObservationSnapshotPublisher implements AutoCloseable {

    static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofSeconds(1L);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(1L);

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(ObservationSnapshotPublisher.class);

    private final List<ObservationSnapshotControl> controls;
    private final long refreshIntervalNanos;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean refreshRequested = new AtomicBoolean();
    private final Object lifecycleLock = new Object();

    private ScheduledExecutorService scheduler;

    public ObservationSnapshotPublisher(List<ObservationSnapshotControl> controls) {
        this(controls, DEFAULT_REFRESH_INTERVAL);
    }

    ObservationSnapshotPublisher(List<ObservationSnapshotControl> controls, Duration refreshInterval) {
        this.controls = List.copyOf(Objects.requireNonNull(controls, "controls"));
        if (this.controls.isEmpty()) {
            throw new IllegalArgumentException("At least one observation control is required");
        }
        this.controls.forEach(control -> Objects.requireNonNull(control, "control"));

        Duration interval = Objects.requireNonNull(refreshInterval, "refreshInterval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("Refresh interval must be positive");
        }
        this.refreshIntervalNanos = interval.toNanos();
    }

    public void start() {
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException("Observation snapshot publisher is closed");
            }
            if (scheduler != null) {
                return;
            }

            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "platform-tracing-jmx-observation-publisher");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.scheduleWithFixedDelay(
                    this::publishNow,
                    refreshIntervalNanos,
                    refreshIntervalNanos,
                    TimeUnit.NANOSECONDS);
        }
    }

    public void publishNow() {
        if (closed.get()) {
            return;
        }

        for (ObservationSnapshotControl control : controls) {
            try {
                control.publishObservationSnapshot();
            } catch (RuntimeException failure) {
                log.warn(
                        AgentEventCodes.CTRL_PLANE,
                        "JMX observation snapshot publication failed",
                        failure,
                        "control", control.getClass().getName());
            }
        }
    }

    public void requestPublish() {
        if (closed.get() || !refreshRequested.compareAndSet(false, true)) {
            return;
        }

        ScheduledExecutorService activeScheduler;
        synchronized (lifecycleLock) {
            activeScheduler = scheduler;
        }
        if (activeScheduler == null) {
            refreshRequested.set(false);
            return;
        }

        try {
            activeScheduler.execute(() -> {
                try {
                    publishNow();
                } finally {
                    refreshRequested.set(false);
                }
            });
        } catch (RejectedExecutionException rejected) {
            refreshRequested.set(false);
            if (!closed.get()) {
                log.warn(
                        AgentEventCodes.CTRL_PLANE,
                        "JMX observation snapshot publication request was rejected",
                        rejected);
            }
        }
    }

    @Override
    public void close() {
        ScheduledExecutorService schedulerToClose;
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }

            schedulerToClose = scheduler;
            scheduler = null;
            refreshRequested.set(false);
            if (schedulerToClose != null) {
                schedulerToClose.shutdownNow();
            }
        }

        if (schedulerToClose == null) {
            return;
        }

        try {
            if (!schedulerToClose.awaitTermination(SHUTDOWN_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)) {
                log.warn(
                        AgentEventCodes.CTRL_PLANE,
                        "JMX observation snapshot publisher did not stop within the shutdown timeout");
            }
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            log.warn(
                    AgentEventCodes.CTRL_PLANE,
                    "Interrupted while stopping JMX observation snapshot publisher",
                    interruption);
        }
    }
}
