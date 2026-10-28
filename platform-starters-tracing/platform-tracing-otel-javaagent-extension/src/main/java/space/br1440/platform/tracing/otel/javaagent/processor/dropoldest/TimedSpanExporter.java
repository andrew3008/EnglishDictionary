package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.RateLimitedLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ExporterLogEvent;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

final class TimedSpanExporter {

    private static final PlatformAgentLogger LIFECYCLE_LOG = PlatformAgentLogger.get(TimedSpanExporter.class);

    private final SpanExporter delegate;
    private final RateLimitedLogger log;
    private final long exportTimeoutNanos;
    private final long shutdownTimeoutNanos;
    private final DropOldestExportMetrics metrics;

    TimedSpanExporter(SpanExporter delegate,
                      long exportTimeoutNanos,
                      long shutdownTimeoutNanos,
                      DropOldestExportMetrics metrics) {
        this(
                delegate,
                exportTimeoutNanos,
                shutdownTimeoutNanos,
                metrics,
                new RateLimitedLogger(TimedSpanExporter.class)
        );
    }

    TimedSpanExporter(SpanExporter delegate,
                      long exportTimeoutNanos,
                      long shutdownTimeoutNanos,
                      DropOldestExportMetrics metrics,
                      RateLimitedLogger log) {
        this.delegate = delegate;
        this.exportTimeoutNanos = exportTimeoutNanos;
        this.shutdownTimeoutNanos = shutdownTimeoutNanos;
        this.metrics = metrics;
        this.log = Objects.requireNonNull(log, "log");
    }

    TimedExport export(List<SpanData> snapshots) {
        CompletableResultCode resultCode;
        try {
            resultCode = delegate.export(snapshots);
        } catch (Throwable failure) {
            PlatformThrowables.propagateIfFatal(failure);
            metrics.incrementFailures();
            log.log(ExporterLogEvent.TIMED_EXPORT_THROWN, fields -> fields.cause(failure));
            return TimedExport.finished(false);
        }

        if (resultCode == null) {
            metrics.incrementFailures();
            log.log(ExporterLogEvent.TIMED_EXPORT_RETURNED_NULL);
            return TimedExport.finished(false);
        }

        boolean completed = resultCode.join(exportTimeoutNanos, TimeUnit.NANOSECONDS).isDone();
        if (!completed) {
            metrics.incrementTimeouts();
            metrics.incrementFailures();
            log.log(ExporterLogEvent.TIMED_EXPORT_TIMED_OUT, fields -> fields.putLong(
                    AgentLogField.TIMEOUT_MILLIS,
                    TimeUnit.NANOSECONDS.toMillis(exportTimeoutNanos)
            ));
            return TimedExport.timedOut(resultCode);
        }

        if (!resultCode.isSuccess()) {
            metrics.incrementFailures();
            log.log(ExporterLogEvent.TIMED_EXPORT_RETURNED_FAILURE);
            return TimedExport.finished(false);
        }

        return TimedExport.finished(true);
    }

    void reportDelegateHeld(long heldNanos) {
        log.log(ExporterLogEvent.TIMED_DELEGATE_SLOT_HELD, fields -> fields.putLong(
                AgentLogField.HELD_MILLIS,
                TimeUnit.NANOSECONDS.toMillis(heldNanos)
        ));
    }

    void reportDelegateReleased(long heldNanos, boolean success) {
        LIFECYCLE_LOG.log(ExporterLogEvent.TIMED_DELEGATE_SLOT_RELEASED, fields -> fields
                .putLong(AgentLogField.HELD_MILLIS, TimeUnit.NANOSECONDS.toMillis(heldNanos))
                .putEnum(AgentLogField.OUTCOME, success ? LateOutcome.SUCCESS : LateOutcome.FAILURE));
    }

    boolean shutdown() {
        CompletableResultCode resultCode;
        try {
            resultCode = delegate.shutdown();
        } catch (Throwable failure) {
            PlatformThrowables.propagateIfFatal(failure);
            metrics.incrementShutdownFailures();
            LIFECYCLE_LOG.log(ExporterLogEvent.TIMED_SHUTDOWN_FAILED, fields -> fields.cause(failure));
            return false;
        }

        if (resultCode == null) {
            metrics.incrementShutdownFailures();
            LIFECYCLE_LOG.log(ExporterLogEvent.TIMED_SHUTDOWN_RETURNED_NULL);
            return false;
        }

        boolean completed = resultCode.join(shutdownTimeoutNanos, TimeUnit.NANOSECONDS).isDone();
        if (!completed) {
            metrics.incrementShutdownFailures();
            LIFECYCLE_LOG.log(ExporterLogEvent.TIMED_SHUTDOWN_TIMED_OUT, fields -> fields.putLong(
                    AgentLogField.TIMEOUT_MILLIS,
                    TimeUnit.NANOSECONDS.toMillis(shutdownTimeoutNanos)
            ));
            return false;
        }

        boolean success = resultCode.isSuccess();
        if (!success) {
            metrics.incrementShutdownFailures();
            LIFECYCLE_LOG.log(ExporterLogEvent.TIMED_SHUTDOWN_RETURNED_FAILURE);
        }

        return success;
    }

    private enum LateOutcome {
        SUCCESS,
        FAILURE
    }
}
