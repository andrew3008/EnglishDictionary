package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.RateLimitedLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ControlPlaneLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

public final class ObservationSnapshotPublisher implements AutoCloseable {

    private static final PlatformAgentLogger lifecycleLog = PlatformAgentLogger.get(ObservationSnapshotPublisher.class);

    static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofSeconds(1L);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(1L);

    private final List<Runnable> publications;
    private final RateLimitedLogger log = new RateLimitedLogger(ObservationSnapshotPublisher.class);
    private final long refreshIntervalNanos;
    private final AtomicReference<PublisherState> state = new AtomicReference<>(PublisherState.created());

    public ObservationSnapshotPublisher(List<Runnable> publications) {
        this(publications, DEFAULT_REFRESH_INTERVAL);
    }

    ObservationSnapshotPublisher(List<Runnable> publications, Duration refreshInterval) {
        this.publications = List.copyOf(Objects.requireNonNull(publications, "publications"));
        if (this.publications.isEmpty()) {
            throw new IllegalArgumentException("At least one observation snapshot publication is required");
        }
        this.publications.forEach(publication -> Objects.requireNonNull(publication, "publication"));

        Duration interval = Objects.requireNonNull(refreshInterval, "refreshInterval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("Refresh interval must be positive");
        }
        this.refreshIntervalNanos = interval.toNanos();
    }

    public void start() {
        PublisherState current = state.get();
        requireOperable(current);
        if (current.running()) {
            return;
        }

        ScheduledExecutorService candidate = newScheduler();
        if (!state.compareAndSet(current, current.started(candidate))) {
            candidate.shutdownNow();
            requireOperable(state.get());
            return;
        }

        try {
            candidate.scheduleWithFixedDelay(
                    this::publishSnapshot,
                    refreshIntervalNanos,
                    refreshIntervalNanos,
                    TimeUnit.NANOSECONDS
            );
        } catch (RejectedExecutionException rejected) {
            if (!state.get().closed()) {
                throw rejected;
            }
        }
    }

    public void publishSnapshot() {
        if (state.get().closed()) {
            return;
        }

        for (Runnable publication : publications) {
            try {
                publication.run();
            } catch (RuntimeException failure) {
                log.log(ControlPlaneLogEvent.JMX_OBSERVATION_SNAPSHOT_PUBLICATION_FAILED, fields -> fields
                        .putString(AgentLogField.SOURCE, publication.getClass().getName())
                        .cause(failure));
            }
        }
    }

    public void requestSnapshotPublication() {
        PublisherState current = state.get();
        while (current.running() && !current.refreshPending()) {
            PublisherState requested = current.refreshRequested();
            if (state.compareAndSet(current, requested)) {
                submitRequestedPublication(requested.scheduler());
                return;
            }

            current = state.get();
        }
    }

    private void submitRequestedPublication(ScheduledExecutorService scheduler) {
        try {
            scheduler.execute(() -> {
                try {
                    publishSnapshot();
                } finally {
                    clearRefreshPending();
                }
            });
        } catch (RejectedExecutionException rejected) {
            clearRefreshPending();
            if (!state.get().closed()) {
                log.log(ControlPlaneLogEvent.JMX_OBSERVATION_SNAPSHOT_PUBLICATION_REJECTED,
                        fields -> fields.cause(rejected));
            }
        }
    }

    @Override
    public void close() {
        PublisherState previous = state.getAndSet(PublisherState.closedState());
        if (previous.closed()) {
            return;
        }

        ScheduledExecutorService scheduler = previous.scheduler();
        if (scheduler == null) {
            return;
        }

        scheduler.shutdownNow();
        awaitTermination(scheduler);
    }

    private void clearRefreshPending() {
        state.updateAndGet(current -> current.refreshPending() ? current.refreshCompleted() : current);
    }

    private static void requireOperable(PublisherState current) {
        if (current.closed()) {
            throw new IllegalStateException("Observation snapshot publisher is closed");
        }
    }

    private static ScheduledExecutorService newScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "platform-tracing-jmx-observation-publisher");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void awaitTermination(ScheduledExecutorService scheduler) {
        try {
            if (!scheduler.awaitTermination(SHUTDOWN_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)) {
                lifecycleLog.log(ControlPlaneLogEvent.JMX_OBSERVATION_SNAPSHOT_PUBLISHER_STOP_TIMEOUT);
            }
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            lifecycleLog.log(ControlPlaneLogEvent.JMX_OBSERVATION_SNAPSHOT_PUBLISHER_STOP_INTERRUPTED,
                    fields -> fields.cause(interruption));
        }
    }

    private record PublisherState(Phase phase, ScheduledExecutorService scheduler, boolean refreshPending) {

        private enum Phase {
            CREATED,
            RUNNING,
            CLOSED
        }

        private PublisherState {
            if ((phase == Phase.RUNNING) ^ (scheduler != null)) {
                throw new IllegalStateException("Publisher scheduler is bound to the RUNNING phase only");
            }

            if (refreshPending && phase != Phase.RUNNING) {
                throw new IllegalStateException("Publisher refresh can be pending in the RUNNING phase only");
            }
        }

        static PublisherState created() {
            return new PublisherState(Phase.CREATED, null, false);
        }

        static PublisherState closedState() {
            return new PublisherState(Phase.CLOSED, null, false);
        }

        boolean running() {
            return phase == Phase.RUNNING;
        }

        boolean closed() {
            return phase == Phase.CLOSED;
        }

        PublisherState started(ScheduledExecutorService startedScheduler) {
            return new PublisherState(Phase.RUNNING, startedScheduler, false);
        }

        PublisherState refreshRequested() {
            return new PublisherState(Phase.RUNNING, scheduler, true);
        }

        PublisherState refreshCompleted() {
            return new PublisherState(Phase.RUNNING, scheduler, false);
        }
    }
}
