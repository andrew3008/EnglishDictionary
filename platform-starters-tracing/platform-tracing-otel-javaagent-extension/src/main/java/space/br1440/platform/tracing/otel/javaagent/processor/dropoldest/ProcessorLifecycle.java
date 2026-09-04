package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;

import io.opentelemetry.sdk.common.CompletableResultCode;

final class ProcessorLifecycle {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(ProcessorLifecycle.class);

    static final String TERMINATOR_THREAD_NAME_PREFIX = "platform-tracing-drop-oldest-shutdown-";

    private static final AtomicInteger TERMINATOR_SEQUENCE = new AtomicInteger();

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
        startTerminator();
        return shutdownResult;
    }

    private void startTerminator() {
        String name = TERMINATOR_THREAD_NAME_PREFIX + TERMINATOR_SEQUENCE.incrementAndGet();
        Thread terminator = new Thread(this::terminate, name);
        terminator.setDaemon(true);
        terminator.start();
    }

    private void terminate() {
        long deadlineNanos = System.nanoTime() + shutdownTimeoutNanos;
        boolean drained = worker.awaitTermination(deadlineNanos - System.nanoTime());
        boolean budgetExceeded = !drained;
        if (budgetExceeded) {
            log.warn(AgentEventCodes.EXPORT_LIFECYCLE, "Processor lifecycle timed out", "timeoutMs",
                    String.valueOf(TimeUnit.NANOSECONDS.toMillis(shutdownTimeoutNanos)));

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
}
