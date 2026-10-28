package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import java.util.ArrayList;
import java.util.List;

import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventType;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ExportPipelineLogEvent;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

import io.opentelemetry.sdk.common.CompletableResultCode;

final class ExportWorker implements Runnable {

    static final String THREAD_NAME = "platform-tracing-drop-oldest-exporter";

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(ExportWorker.class);

    private final ExportBuffer buffer;
    private final TimedSpanExporter exporter;
    private final long scheduleDelayNanos;
    private final long shutdownTimeoutNanos;
    private final CompletableResultCode shutdownResult = new CompletableResultCode();

    ExportWorker(ExportBuffer buffer,
                 TimedSpanExporter exporter,
                 long scheduleDelayNanos,
                 long shutdownTimeoutNanos) {
        this.buffer = buffer;
        this.exporter = exporter;
        this.scheduleDelayNanos = scheduleDelayNanos;
        this.shutdownTimeoutNanos = shutdownTimeoutNanos;
    }

    void start() {
        Thread thread = new Thread(this, THREAD_NAME);
        thread.setDaemon(true);
        thread.start();
    }

    CompletableResultCode shutdown() {
        buffer.beginShutdown(shutdownTimeoutNanos);
        return shutdownResult;
    }

    @Override
    public void run() {
        WorkerStopReason stopReason = WorkerStopReason.WORKER_FAILED;
        FailureCollector failures = new FailureCollector();
        boolean loopFailed = false;
        try {
            stopReason = exportUntilShutdown();
        } catch (Throwable failure) {
            loopFailed = true;
            failures.add(failure);
            failBuffer(failures);
        }

        terminate(stopReason, failures);
        failures.propagateFatal();

        Throwable failure = failures.combineForLogging();
        if (failure == null) {
            return;
        }

        AgentLogEventType eventType = loopFailed
                ? ExportPipelineLogEvent.EXPORT_WORKER_TERMINATED_ABNORMALLY
                : ExportPipelineLogEvent.EXPORTER_SHUTDOWN_FAILED_AFTER_WORKER_DRAIN;
        log.log(eventType, fields -> fields.cause(failure));
    }

    private WorkerStopReason exportUntilShutdown() {
        long lastExportNanos = buffer.currentTimeNanos();
        while (true) {
            WorkerAction action = buffer.awaitNextAction(scheduleDelayNanos, lastExportNanos);
            switch (action) {
                case WorkerAction.ExportBatch exportBatch -> {
                    exportBatch(exportBatch.batch());
                    lastExportNanos = buffer.currentTimeNanos();
                }

                case WorkerAction.DelegateHeld delegateHeld -> exporter.reportDelegateHeld(delegateHeld.heldNanos());

                case WorkerAction.StopWorker stopWorker -> {
                    return stopWorker.reason();
                }
            }
        }
    }

    private void exportBatch(SpanQueue.Batch batch) {
        TimedExport outcome = exporter.export(batch.spans());
        buffer.completeExport(batch, outcome.success(), outcome.unfinished() != null);

        CompletableResultCode unfinished = outcome.unfinished();
        if (unfinished != null) {
            unfinished.whenComplete(() -> {
                long heldNanos = buffer.releaseDelegate();
                exporter.reportDelegateReleased(heldNanos, unfinished.isSuccess());
            });
        }
    }

    private void failBuffer(FailureCollector failures) {
        try {
            buffer.failTerminal();
        } catch (Throwable failTerminalFailure) {
            failures.add(failTerminalFailure);
        }
    }

    private void terminate(WorkerStopReason stopReason,
                           FailureCollector failures) {
        boolean exporterShutdownSucceeded = false;
        try {
            exporterShutdownSucceeded = exporter.shutdown();
        } catch (Throwable shutdownFailure) {
            failures.add(shutdownFailure);
        }

        boolean lifecycleSettled = false;
        boolean workerFailed = true;
        try {
            workerFailed = buffer.markTerminated();
            lifecycleSettled = true;
        } catch (Throwable settleFailure) {
            failures.add(settleFailure);
        } finally {
            boolean succeeded = lifecycleSettled
                    && stopReason == WorkerStopReason.DRAIN_COMPLETED
                    && !workerFailed
                    && exporterShutdownSucceeded;
            publishShutdownResult(succeeded, failures);
        }
    }

    private void publishShutdownResult(boolean succeeded, FailureCollector failures) {
        try {
            if (succeeded) {
                shutdownResult.succeed();
            } else {
                shutdownResult.fail();
            }
        } catch (Throwable publicationFailure) {
            failures.add(publicationFailure);
            if (!shutdownResult.isDone()) {
                try {
                    shutdownResult.fail();
                } catch (Throwable fallbackFailure) {
                    failures.add(fallbackFailure);
                }
            }
        }
    }

    private static final class FailureCollector {
        private final List<Throwable> failures = new ArrayList<>(3);

        private void add(Throwable failure) {
            failures.add(failure);
        }

        private void propagateFatal() {
            for (Throwable failure : failures) {
                PlatformThrowables.propagateIfFatal(failure);
            }
        }

        private Throwable combineForLogging() {
            if (failures.isEmpty()) {
                return null;
            }

            Throwable primary = failures.getFirst();
            for (int index = 1; index < failures.size(); index++) {
                Throwable secondary = failures.get(index);
                if (secondary != primary) {
                    primary.addSuppressed(secondary);
                }
            }
            return primary;
        }
    }
}
