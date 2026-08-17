package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

import io.opentelemetry.sdk.trace.data.SpanData;

final class ExportWorker implements Runnable {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(ExportWorker.class);

    private final ExportBuffer buffer;
    private final TimedSpanExporter exporter;
    private final long scheduleDelayNanos;
    private final CountDownLatch terminated = new CountDownLatch(1);

    ExportWorker(ExportBuffer buffer, TimedSpanExporter exporter, long scheduleDelayNanos) {
        this.buffer = buffer;
        this.exporter = exporter;
        this.scheduleDelayNanos = scheduleDelayNanos;
    }

    void start() {
        Thread thread = new Thread(this, "platform-tracing-drop-oldest-exporter");
        thread.setDaemon(true);
        thread.start();
    }

    boolean awaitTermination(long timeoutNanos) {
        try {
            return terminated.await(Math.max(0, timeoutNanos), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    void awaitTerminationUnbounded() {
        boolean interrupted = false;
        while (terminated.getCount() != 0) {
            try {
                terminated.await();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void run() {
        long lastExportNanos = System.nanoTime();
        try {
            while (true) {
                ExportBuffer.WorkUnit work = buffer.awaitWork(scheduleDelayNanos, lastExportNanos);
                if (work.isTerminate()) {
                    return;
                }

                exportBatch(work.batch());
                lastExportNanos = System.nanoTime();
            }
        } catch (Throwable failure) {
            buffer.failTerminal();
            PlatformThrowables.propagateIfFatal(failure);
            log.error(AgentEventCodes.EXPORT_WORKER, "Export worker terminated abnormally", failure);
        } finally {
            terminated.countDown();
        }
    }

    private void exportBatch(List<SequencedSpan> batch) {
        if (batch.isEmpty()) {
            return;
        }

        List<SpanData> snapshots = new ArrayList<>(batch.size());
        for (SequencedSpan sequencedSpan : batch) {
            snapshots.add(sequencedSpan.snapshot());
        }

        TimedSpanExporter.ExportOutcome outcome = exporter.export(snapshots);
        if (outcome.timedOut()) {
            ExportBuffer.InFlightExport pending = buffer.registerTimedOut(batch, outcome.pendingResult());
            outcome.pendingResult().whenComplete(() -> buffer.completeTimedOut(pending));
            return;
        }
        buffer.settle(batch, outcome.success());
    }
}
