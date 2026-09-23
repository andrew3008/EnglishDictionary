package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import java.util.List;
import java.util.concurrent.TimeUnit;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;
import space.br1440.platform.tracing.otel.javaagent.resilience.RateLimitedLogger;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

final class TimedSpanExporter {

    private static final RateLimitedLogger log = new RateLimitedLogger(TimedSpanExporter.class);

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

    boolean export(List<SpanData> snapshots) {
        CompletableResultCode resultCode;
        try {
            resultCode = delegate.export(snapshots);
        } catch (Throwable failure) {
            PlatformThrowables.propagateIfFatal(failure);
            metrics.incrementFailures();
            log.warn(AgentEventCodes.EXPORT_TIMED, "exporter.export() threw", failure);
            return false;
        }

        if (resultCode == null) {
            metrics.incrementFailures();
            log.warn(AgentEventCodes.EXPORT_TIMED, "exporter.export() returned null");
            return false;
        }

        boolean completed = resultCode.join(exportTimeoutNanos, TimeUnit.NANOSECONDS).isDone();
        if (!completed) {
            metrics.incrementTimeouts();
            metrics.incrementFailures();
            log.warn(AgentEventCodes.EXPORT_TIMED, "exporter.export() timed out", "timeoutMs",
                    String.valueOf(TimeUnit.NANOSECONDS.toMillis(exportTimeoutNanos)));
            return false;
        }

        if (!resultCode.isSuccess()) {
            metrics.incrementFailures();
            log.warn(AgentEventCodes.EXPORT_TIMED, "exporter.export() returned failure");
            return false;
        }

        return true;
    }

    boolean shutdown() {
        CompletableResultCode resultCode;
        try {
            resultCode = delegate.shutdown();
        } catch (Throwable failure) {
            PlatformThrowables.propagateIfFatal(failure);
            metrics.incrementShutdownFailures();
            log.warn(AgentEventCodes.EXPORT_TIMED, "exporter.shutdown() failed", failure);
            return false;
        }

        if (resultCode == null) {
            metrics.incrementShutdownFailures();
            log.warn(AgentEventCodes.EXPORT_TIMED, "exporter.shutdown() returned null");
            return false;
        }

        boolean completed = resultCode.join(shutdownTimeoutNanos, TimeUnit.NANOSECONDS).isDone();
        if (!completed) {
            metrics.incrementShutdownFailures();
            log.warn(AgentEventCodes.EXPORT_TIMED, "exporter.shutdown() timed out", "timeoutMs",
                    String.valueOf(TimeUnit.NANOSECONDS.toMillis(shutdownTimeoutNanos)));
            return false;
        }

        boolean success = resultCode.isSuccess();
        if (!success) {
            metrics.incrementShutdownFailures();
            log.warn(AgentEventCodes.EXPORT_TIMED, "exporter.shutdown() returned failure");
        }

        return success;
    }
}
