package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.opentelemetry.sdk.common.CompletableResultCode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class ProcessorLifecycle {

    private static final int TERMINATOR_POOL_SIZE = 2;
    private static final AtomicInteger TERMINATOR_SEQUENCE = new AtomicInteger();
    private static final Executor TERMINATOR_EXECUTOR = Executors.newFixedThreadPool(TERMINATOR_POOL_SIZE, terminatorThreadFactory());

    private final ExportBuffer buffer;
    private final ExportWorker worker;
    private final TimedSpanExporter exporter;
    private final long shutdownTimeoutNanos;
    private final CompletableResultCode shutdownResult = new CompletableResultCode();
    private final AtomicBoolean shutdownInitiated = new AtomicBoolean();

    ProcessorLifecycle(ExportBuffer buffer, ExportWorker worker, TimedSpanExporter exporter, long shutdownTimeoutNanos) {
        this.buffer = buffer;
        this.worker = worker;
        this.exporter = exporter;
        this.shutdownTimeoutNanos = shutdownTimeoutNanos;
    }

    CompletableResultCode shutdown() {
        if (!shutdownInitiated.compareAndSet(false, true)) {
            return shutdownResult;
        }

        buffer.beginShutdown();
        TERMINATOR_EXECUTOR.execute(this::terminate);
        return shutdownResult;
    }

    private void terminate() {
        long deadlineNanos = System.nanoTime() + shutdownTimeoutNanos;
        boolean drained = worker.awaitTermination(deadlineNanos - System.nanoTime());
        boolean budgetExceeded = !drained;
        if (budgetExceeded) {
            log.warn("""
                        PlatformDropOldestExportSpanProcessor: worker did not stop within {} ms; \
                        discarding queued spans, exporter.shutdown() deferred until active export finishes""",
                    TimeUnit.NANOSECONDS.toMillis(shutdownTimeoutNanos)
            );

            buffer.forceDiscardQueuedAndFailFlushes();
            worker.awaitTerminationUnbounded();
        }

        boolean exporterShutdownSucceeded = exporter.shutdown();
        boolean workerFailed = buffer.markTerminated();
        if (!budgetExceeded && !workerFailed && exporterShutdownSucceeded) {
            shutdownResult.succeed();
        } else {
            shutdownResult.fail();
        }
    }

    private static ThreadFactory terminatorThreadFactory() {
        return runnable -> {
            String name = "platform-tracing-drop-oldest-shutdown-" + TERMINATOR_SEQUENCE.incrementAndGet();
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
