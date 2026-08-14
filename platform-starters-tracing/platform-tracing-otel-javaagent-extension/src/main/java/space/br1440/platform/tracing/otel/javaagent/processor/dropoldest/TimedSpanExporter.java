package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.LoggerFactory;

import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;
import space.br1440.platform.tracing.otel.javaagent.resilience.RateLimitedLogger;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

final class TimedSpanExporter {

    private static final RateLimitedLogger log = new RateLimitedLogger(
            LoggerFactory.getLogger(PlatformDropOldestExportSpanProcessor.class)
    );

    private final SpanExporter delegate;
    private final long exportTimeoutNanos;
    private final long shutdownTimeoutNanos;
    private final DropOldestExportMetrics metrics;

    TimedSpanExporter(SpanExporter delegate,
                      long exportTimeoutNanos,
                      long shutdownTimeoutNanos,
                      DropOldestExportMetrics metrics) {
        this.delegate = delegate;
        this.exportTimeoutNanos = exportTimeoutNanos;
        this.shutdownTimeoutNanos = shutdownTimeoutNanos;
        this.metrics = metrics;
    }

    ExportOutcome export(List<SpanData> snapshots) {
        CompletableResultCode resultCode;
        try {
            resultCode = delegate.export(snapshots);
        } catch (Throwable failure) {
            PlatformThrowables.propagateIfFatal(failure);
            metrics.incrementFailures();
            log.warn("exporter.export() threw: {}", failure.getMessage());
            return ExportOutcome.FAILURE;
        }

        if (resultCode == null) {
            metrics.incrementFailures();
            log.warn("exporter.export() returned null");
            return ExportOutcome.FAILURE;
        }

        boolean completed = resultCode.join(exportTimeoutNanos, TimeUnit.NANOSECONDS).isDone();
        if (!completed) {
            metrics.incrementTimeouts();
            metrics.incrementFailures();
            log.warn("exporter.export() timed out after {} ms", TimeUnit.NANOSECONDS.toMillis(exportTimeoutNanos));
            return ExportOutcome.timedOut(resultCode);
        }

        if (!resultCode.isSuccess()) {
            metrics.incrementFailures();
            log.warn("exporter.export() returned failure");
            return ExportOutcome.FAILURE;
        }

        return ExportOutcome.SUCCESS;
    }

    boolean shutdown() {
        CompletableResultCode resultCode;
        try {
            resultCode = delegate.shutdown();
        } catch (Throwable failure) {
            PlatformThrowables.propagateIfFatal(failure);
            metrics.incrementShutdownFailures();
            log.warn("exporter.shutdown() failed: {}", failure.getMessage());
            return false;
        }

        if (resultCode == null) {
            metrics.incrementShutdownFailures();
            log.warn("exporter.shutdown() returned null");
            return false;
        }

        boolean completed = resultCode.join(shutdownTimeoutNanos, TimeUnit.NANOSECONDS).isDone();
        if (!completed) {
            metrics.incrementShutdownFailures();
            log.warn("exporter.shutdown() timed out after {} ms", TimeUnit.NANOSECONDS.toMillis(shutdownTimeoutNanos));
            return false;
        }

        boolean success = resultCode.isSuccess();
        if (!success) {
            metrics.incrementShutdownFailures();
            log.warn("exporter.shutdown() returned failure");
        }

        return success;
    }

    record ExportOutcome(boolean timedOut, boolean success, CompletableResultCode pendingResult) {

        static final ExportOutcome SUCCESS = new ExportOutcome(false, true, null);
        static final ExportOutcome FAILURE = new ExportOutcome(false, false, null);

        static ExportOutcome timedOut(CompletableResultCode pendingResult) {
            return new ExportOutcome(true, false, pendingResult);
        }
    }
}
