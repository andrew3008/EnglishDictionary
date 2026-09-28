package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.SpanTimeoutLogEvent;

final class SpanTimeoutScanner {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(SpanTimeoutScanner.class);

    private static final String SCANNER_THREAD_NAME = "platform-tracing-span-timeout";
    private static final long SHUTDOWN_TIMEOUT_MILLIS = 2_000L;
    private static final long NEVER_SUCCEEDED_EPOCH_MILLIS = 0L;

    private final SpanTimeoutScan scan;
    private final LongSupplier epochMillis;
    private final AtomicLong lastSuccessfulScanEpochMillis = new AtomicLong(NEVER_SUCCEEDED_EPOCH_MILLIS);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private volatile ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> scanTask;
    private volatile boolean stopResult;

    SpanTimeoutScanner(SpanTimeoutScan scan, LongSupplier epochMillis) {
        this.scan = Objects.requireNonNull(scan, "scan");
        this.epochMillis = Objects.requireNonNull(epochMillis, "epochMillis");
    }

    void start(Duration scanInterval) {
        Objects.requireNonNull(scanInterval, "scanInterval");
        if (scheduler != null) {
            throw new IllegalStateException("span-timeout scanner already started");
        }

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, SCANNER_THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });

        try {
            long intervalNanos = scanInterval.toNanos();
            this.scanTask = executor.scheduleWithFixedDelay(
                    this::runBackgroundScan, intervalNanos, intervalNanos, TimeUnit.NANOSECONDS
            );
            this.scheduler = executor;
        } catch (RuntimeException | Error failure) {
            executor.shutdownNow();
            throw failure;
        }
    }

    boolean stop() {
        if (!stopped.compareAndSet(false, true)) {
            return stopResult;
        }

        boolean result = doStop();
        stopResult = result;
        return result;
    }

    private boolean doStop() {
        ScheduledExecutorService executor = scheduler;
        if (executor == null) {
            return true;
        }

        if (state() == SpanTimeoutScannerState.TERMINATED_ABNORMALLY) {
            log.log(SpanTimeoutLogEvent.SPAN_TIMEOUT_SCANNER_PREVIOUSLY_TERMINATED_ABNORMALLY);
        }

        scanTask.cancel(false);
        executor.shutdownNow();
        try {
            if (executor.awaitTermination(SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                return true;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.log(SpanTimeoutLogEvent.SPAN_TIMEOUT_SCANNER_STOP_INTERRUPTED);
            return false;
        }

        log.log(SpanTimeoutLogEvent.SPAN_TIMEOUT_SCANNER_STOP_TIMED_OUT,
                fields -> fields.putLong(AgentLogField.TIMEOUT_MILLIS, SHUTDOWN_TIMEOUT_MILLIS));
        return false;
    }

    SpanTimeoutScannerState state() {
        ScheduledFuture<?> task = scanTask;
        if (task == null) {
            return SpanTimeoutScannerState.DISABLED;
        }

        if (task.isCancelled()) {
            return SpanTimeoutScannerState.STOPPED;
        }

        if (task.isDone()) {
            return SpanTimeoutScannerState.TERMINATED_ABNORMALLY;
        }

        return SpanTimeoutScannerState.RUNNING;
    }

    long lastSuccessfulScanEpochMillis() {
        return lastSuccessfulScanEpochMillis.get();
    }

    private void runBackgroundScan() {
        try {
            if (scan.run()) {
                lastSuccessfulScanEpochMillis.set(epochMillis.getAsLong());
            }
        } catch (Throwable failure) {
            log.log(SpanTimeoutLogEvent.SPAN_TIMEOUT_SCANNER_TERMINATED_ABNORMALLY,
                    fields -> fields.cause(failure));
            throw failure;
        }
    }
}
