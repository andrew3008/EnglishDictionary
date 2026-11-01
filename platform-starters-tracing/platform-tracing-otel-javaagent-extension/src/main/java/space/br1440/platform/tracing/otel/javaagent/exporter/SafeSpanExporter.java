package space.br1440.platform.tracing.otel.javaagent.exporter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.otel.javaagent.configuration.OtelSdkDefaults;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.exporter.boundary.SpanBoundaryOutcome;
import space.br1440.platform.tracing.otel.javaagent.exporter.boundary.SpanDataBoundaryScrubber;
import space.br1440.platform.tracing.otel.javaagent.exporter.guard.OtlpRequestSizeLimitingSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseObservation;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.RateLimitedLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventType;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ExporterLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ScrubbingLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.ratelimit.MinIntervalRateLimiter;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

public final class SafeSpanExporter implements SpanExporter {

    private static final long RECOVERY_PUBLICATION_INTERVAL_NANOS = Duration.ofMinutes(1L).toNanos();
    private static final PlatformAgentLogger LIFECYCLE_LOG = PlatformAgentLogger.get(SafeSpanExporter.class);

    private final OtlpRequestSizeLimitingSpanExporter delegate;
    private final TracingSettingsSource acceptedPolicy;
    private final ScrubbingRuntime scrubbingRuntime;
    private final SpanDataBoundaryScrubber boundaryScrubber;
    private final RateLimitedLogger exportLog;
    private final RateLimitedLogger diagnosticLog;
    private final MinIntervalRateLimiter recoveryPublication;
    private final ExportOutageTracker outageTracker = new ExportOutageTracker();

    private final LongAdder exportBatches = new LongAdder();
    private final LongAdder exportBatchFailures = new LongAdder();
    private final LongAdder exportedSpans = new LongAdder();
    private final LongAdder transportDroppedSpans = new LongAdder();
    private final LongAdder partialSuccessBatches = new LongAdder();
    private final LongAdder rejectedSpans = new LongAdder();
    private final LongAdder acceptedUnknownBatches = new LongAdder();
    private final LongAdder flushFailures = new LongAdder();
    private final LongAdder shutdownFailures = new LongAdder();
    private final LongAdder suppressedSpansExportDisabled = new LongAdder();
    private final LongAdder oversizedSpansDropped = new LongAdder();
    private final LongAdder sizeMeasurementFailures = new LongAdder();
    private final LongAdder splitExportBatches = new LongAdder();

    public SafeSpanExporter(OtlpRequestSizeLimitingSpanExporter delegate,
                            TracingSettingsSource acceptedPolicy,
                            ScrubbingRuntime scrubbingRuntime) {
        this(
                delegate,
                acceptedPolicy,
                scrubbingRuntime,
                new RateLimitedLogger(SafeSpanExporter.class),
                new RateLimitedLogger(SafeSpanExporter.class),
                System::nanoTime
        );
    }

    SafeSpanExporter(OtlpRequestSizeLimitingSpanExporter delegate,
                     TracingSettingsSource acceptedPolicy,
                     ScrubbingRuntime scrubbingRuntime,
                     RateLimitedLogger exportLog,
                     RateLimitedLogger diagnosticLog,
                     LongSupplier nanoClock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.acceptedPolicy = Objects.requireNonNull(acceptedPolicy, "acceptedPolicy");
        this.scrubbingRuntime = Objects.requireNonNull(scrubbingRuntime, "scrubbingRuntime");
        this.exportLog = Objects.requireNonNull(exportLog, "exportLog");
        this.diagnosticLog = Objects.requireNonNull(diagnosticLog, "diagnosticLog");
        this.recoveryPublication = new MinIntervalRateLimiter(
                RECOVERY_PUBLICATION_INTERVAL_NANOS,
                Objects.requireNonNull(nanoClock, "nanoClock")
        );
        this.boundaryScrubber = new SpanDataBoundaryScrubber(this.scrubbingRuntime);
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        int originalCount = -1;
        List<SpanData> batch;
        try {
            originalCount = spans.size();
            if (originalCount == 0) {
                return CompletableResultCode.ofSuccess();
            }

            if (!acceptedPolicy.current().exporterEnabled()) {
                suppressedSpansExportDisabled.add(originalCount);
                return CompletableResultCode.ofSuccess();
            }

            batch = prepareBatch(spans);
        } catch (Throwable failure) {
            PlatformThrowables.propagateIfFatal(failure);
            recordBoundaryBatchPreparationFailure(originalCount, failure);
            return CompletableResultCode.ofFailure();
        }

        if (batch.isEmpty()) {
            return CompletableResultCode.ofSuccess();
        }

        return send(batch);
    }

    private List<SpanData> prepareBatch(Collection<SpanData> batch) {
        if (batch.isEmpty()) {
            return List.of();
        }

        boolean scrubbingEnabled = scrubbingRuntime.enabled();
        if (scrubbingEnabled) {
            scrubbingRuntime.recordBoundaryExporterInvocation();
        }

        List<SpanData> prepared = new ArrayList<>(batch.size());
        for (SpanData spanData : batch) {
            SpanData candidate;
            if (scrubbingEnabled) {
                SpanBoundaryOutcome outcome = boundaryScrubber.scrub(spanData);
                if (outcome.dropSpan()) {
                    continue;
                }

                candidate = outcome.spanData();
            } else {
                candidate = Objects.requireNonNull(spanData, "spanData");
            }

            prepared.add(candidate);
        }

        return prepared;
    }

    private CompletableResultCode send(List<SpanData> batch) {
        exportBatches.increment();
        ExportResponseObservation observation = new ExportResponseObservation(batch.size());
        long attemptSequence = outageTracker.startAttempt();
        try {
            CompletableResultCode result = delegate.export(batch, observation);
            result.whenComplete(() -> onExportComplete(result, observation, attemptSequence));
            return result;
        } catch (Throwable ex) {
            PlatformThrowables.propagateIfFatal(ex);
            recordFailureMetrics(batch.size(), attemptSequence, ExporterLogEvent.SAFE_EXPORT_THROWN, ex);
            return CompletableResultCode.ofFailure();
        }
    }

    private void onExportComplete(CompletableResultCode result,
                                  ExportResponseObservation observation,
                                  long attemptSequence) {
        try {
            ExportResponseObservation.AggregateResult aggregateResult = Objects.requireNonNull(observation.aggregateResult(), "aggregateResult");
            recordAggregateResult(aggregateResult, attemptSequence, result.getFailureThrowable());
        } catch (Throwable ex) {
            PlatformThrowables.propagateIfFatal(ex);
            diagnosticLog.log(ExporterLogEvent.EXPORT_COMPLETION_CALLBACK_FAILED, fields -> fields.cause(ex));
        }
    }

    private void recordAggregateResult(ExportResponseObservation.AggregateResult aggregate,
                                       long attemptSequence,
                                       @Nullable Throwable error) {
        exportedSpans.add(aggregate.accepted());
        rejectedSpans.add(aggregate.rejected());
        oversizedSpansDropped.add(aggregate.locallyDropped());
        sizeMeasurementFailures.add(aggregate.measurementFailed());

        if (aggregate.requests() > 1) {
            splitExportBatches.increment();
        }

        if (aggregate.rejected() > 0) {
            partialSuccessBatches.increment();
        }

        if (aggregate.undetermined() > 0) {
            acceptedUnknownBatches.increment();
        }

        if (aggregate.transportDropped() > 0) {
            recordFailureMetrics(
                    aggregate.transportDropped(),
                    attemptSequence,
                    ExporterLogEvent.SAFE_EXPORT_RETURNED_FAILURE,
                    error
            );
        } else if (aggregate.accepted() + aggregate.rejected() + aggregate.undetermined() > 0) {
            long failures = outageTracker.recordSuccess(attemptSequence);
            if (failures > 0L) {
                tryPublishRecovery(failures);
            }
        }

        if (aggregate.locallyDropped() > 0) {
            exportLog.log(ExporterLogEvent.SAFE_EXPORT_OVERSIZED_SPAN_DROPPED, fields -> fields
                    .putLong(AgentLogField.COUNT, aggregate.locallyDropped())
                    .putLong(AgentLogField.LIMIT_BYTES, OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES));
        }

        if (aggregate.measurementFailed() > 0) {
            diagnosticLog.log(ExporterLogEvent.SAFE_EXPORT_SIZE_MEASUREMENT_FAILED, fields -> fields
                    .putLong(AgentLogField.COUNT, aggregate.measurementFailed())
                    .cause(aggregate.measurementCause()));
        }

        if (aggregate.undetermined() > 0) {
            diagnosticLog.log(ExporterLogEvent.EXPORT_ACCEPTED_SPAN_COUNT_UNAVAILABLE);
        }
    }

    @Override
    public CompletableResultCode flush() {
        try {
            return observeLifecycleResult(delegate.flush(), flushFailures);
        } catch (Throwable ex) {
            PlatformThrowables.propagateIfFatal(ex);
            flushFailures.increment();
            logFailure(diagnosticLog, ExporterLogEvent.SAFE_FLUSH_THROWN, ex);
            return CompletableResultCode.ofFailure();
        }
    }

    @Override
    public CompletableResultCode shutdown() {
        try {
            return observeShutdownResult(delegate.shutdown());
        } catch (Throwable ex) {
            PlatformThrowables.propagateIfFatal(ex);
            shutdownFailures.increment();
            logLifecycleFailure(ExporterLogEvent.SAFE_SHUTDOWN_THROWN, ex);
            return CompletableResultCode.ofFailure();
        }
    }

    private void recordBoundaryBatchPreparationFailure(int originalCount, Throwable failure) {
        scrubbingRuntime.recordBoundaryBatchPreparationFailure();
        if (originalCount > 0) {
            scrubbingRuntime.recordBoundarySpansDroppedFailClosed(originalCount);
        }

        exportLog.log(ScrubbingLogEvent.SCRUB_BOUNDARY_BATCH_PREPARATION_FAILED, fields -> fields.cause(failure));
    }

    private void recordFailureMetrics(int count,
                                      long attemptSequence,
                                      AgentLogEventType eventType,
                                      @Nullable Throwable error) {
        outageTracker.recordFailure(attemptSequence);
        exportBatchFailures.increment();
        transportDroppedSpans.add(count);
        logFailure(exportLog, eventType, error);
    }

    private void tryPublishRecovery(long failures) {
        if (!recoveryPublication.tryAcquire()) {
            return;
        }

        LIFECYCLE_LOG.log(ExporterLogEvent.SAFE_EXPORT_SUCCEEDED_AFTER_FAILURES, fields -> fields
                .putLong(AgentLogField.FAILURES, failures));
    }

    private void logFailure(RateLimitedLogger logger, AgentLogEventType eventType, @Nullable Throwable error) {
        if (error != null) {
            logger.log(eventType, fields -> fields.cause(error));
        } else {
            logger.log(eventType);
        }
    }

    private void logLifecycleFailure(AgentLogEventType eventType, @Nullable Throwable error) {
        if (error != null) {
            LIFECYCLE_LOG.log(eventType, fields -> fields.cause(error));
        } else {
            LIFECYCLE_LOG.log(eventType);
        }
    }

    private CompletableResultCode observeLifecycleResult(CompletableResultCode result, LongAdder failures) {
        result.whenComplete(() -> {
            if (!result.isSuccess()) {
                failures.increment();
                logFailure(diagnosticLog, ExporterLogEvent.SAFE_FLUSH_RETURNED_FAILURE, null);
            }
        });
        return result;
    }

    private CompletableResultCode observeShutdownResult(CompletableResultCode result) {
        result.whenComplete(() -> {
            if (!result.isSuccess()) {
                shutdownFailures.increment();
                logLifecycleFailure(ExporterLogEvent.SAFE_SHUTDOWN_RETURNED_FAILURE, null);
            }
        });
        return result;
    }

    public Map<String, Long> metricsSnapshot() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        snapshot.put(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORT_BATCHES, exportBatches.sum());
        snapshot.put(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORT_BATCH_FAILURES, exportBatchFailures.sum());
        snapshot.put(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORTED_SPANS, exportedSpans.sum());
        snapshot.put(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_TRANSPORT_DROPPED_SPANS, transportDroppedSpans.sum());
        snapshot.put(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_PARTIAL_SUCCESS_BATCHES, partialSuccessBatches.sum());
        snapshot.put(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_REJECTED_SPANS, rejectedSpans.sum());
        snapshot.put(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_ACCEPTED_UNKNOWN_BATCHES, acceptedUnknownBatches.sum());
        snapshot.put(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_FLUSH_FAILURES, flushFailures.sum());
        snapshot.put(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_SHUTDOWN_FAILURES, shutdownFailures.sum());
        snapshot.put(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_SUPPRESSED_SPANS_EXPORT_DISABLED, suppressedSpansExportDisabled.sum());
        snapshot.put(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_OVERSIZED_SPANS_DROPPED, oversizedSpansDropped.sum());
        snapshot.put(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_SIZE_MEASUREMENT_FAILURES, sizeMeasurementFailures.sum());
        snapshot.put(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_SPLIT_EXPORT_BATCHES, splitExportBatches.sum());
        return snapshot;
    }
}
