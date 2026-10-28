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

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseObservation;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseScope;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.RateLimitedLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventType;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ExporterLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ScrubbingLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.ratelimit.MinIntervalRateLimiter;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingBoundaryMetrics;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

public final class SafeSpanExporter implements SpanExporter {

    private static final long RECOVERY_PUBLICATION_INTERVAL_NANOS = Duration.ofMinutes(1L).toNanos();
    private static final PlatformAgentLogger LIFECYCLE_LOG = PlatformAgentLogger.get(SafeSpanExporter.class);

    private final SpanExporter delegate;
    private final TracingSettingsSource acceptedPolicy;
    private final ScrubbingRuntime scrubbingRuntime;
    private final SpanDataBoundaryScrubber boundaryScrubber;
    private final RateLimitedLogger exportLog;
    private final RateLimitedLogger diagnosticLog;
    private final String exporterType;
    private final MinIntervalRateLimiter recoveryPublication;
    private final Object recoveryLock = new Object();

    private long operationSeq;
    private long episodeFailures;
    private long lastFailureSeq;
    private long lastSuccessSeq;

    private final LongAdder exportBatches = new LongAdder();
    private final LongAdder exportFailures = new LongAdder();
    private final LongAdder exportedSpans = new LongAdder();
    private final LongAdder droppedSpans = new LongAdder();
    private final LongAdder partialSuccessBatches = new LongAdder();
    private final LongAdder rejectedSpans = new LongAdder();
    private final LongAdder acceptedUnknownBatches = new LongAdder();
    private final LongAdder flushFailures = new LongAdder();
    private final LongAdder shutdownFailures = new LongAdder();
    private final LongAdder gatedSpans = new LongAdder();

    public SafeSpanExporter(SpanExporter delegate,
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

    SafeSpanExporter(SpanExporter delegate,
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
        this.exporterType = exporterType(delegate);
    }

    @Override
    public CompletableResultCode export(@Nullable Collection<SpanData> spans) {
        Collection<SpanData> batch = (spans == null) ? List.of() : spans;

        int originalCount = -1;
        int effectiveCount;
        try {
            originalCount = batch.size();

            if (!acceptedPolicy.current().exporterEnabled()) {
                gatedSpans.add(originalCount);
                return CompletableResultCode.ofSuccess();
            }

            batch = applyBoundaryScrubbing(batch);
            effectiveCount = batch.size();
        } catch (Throwable failure) {
            PlatformThrowables.propagateIfFatal(failure);
            recordBoundaryBatchPreparationFailure(originalCount, failure);
            return CompletableResultCode.ofFailure();
        }

        if (effectiveCount == 0 && originalCount > 0) {
            return CompletableResultCode.ofSuccess();
        }

        exportBatches.increment();
        ExportResponseObservation observation = new ExportResponseObservation(effectiveCount);
        try (ExportResponseScope.Handle ignored = ExportResponseScope.open(observation)) {
            long operationSequence = nextOperationSequence();
            try {
                CompletableResultCode result = delegate.export(batch);
                if (result == null) {
                    recordSyncFailure(
                            effectiveCount,
                            operationSequence,
                            ExporterLogEvent.SAFE_EXPORT_RETURNED_NULL
                    );
                    return CompletableResultCode.ofFailure();
                }

                result.whenComplete(() -> onExportComplete(result, observation, operationSequence));
                return result;
            } catch (Throwable ex) {
                PlatformThrowables.propagateIfFatal(ex);
                recordSyncFailure(
                        effectiveCount,
                        operationSequence,
                        ExporterLogEvent.SAFE_EXPORT_THROWN,
                        ex
                );
                return CompletableResultCode.ofFailure();
            }
        }
    }

    private Collection<SpanData> applyBoundaryScrubbing(Collection<SpanData> batch) {
        if (!scrubbingRuntime.enabled() || batch.isEmpty()) {
            return batch;
        }

        scrubbingRuntime.recordBoundaryExporterInvocation();

        List<SpanData> ordered;
        boolean snapshotRequired;
        if (batch instanceof List<SpanData> list) {
            ordered = list;
            snapshotRequired = false;
        } else {
            ordered = new ArrayList<>(batch);
            snapshotRequired = true;
        }

        List<SpanData> result = null;
        int index = 0;
        for (SpanData spanData : ordered) {
            SpanBoundaryOutcome outcome = boundaryScrubber.scrub(spanData);
            boolean unchanged = !outcome.dropSpan() && (outcome.spanData() == spanData);

            if (!unchanged && result == null) {
                result = new ArrayList<>(ordered.size());
                result.addAll(ordered.subList(0, index));
            }

            if (result != null && !outcome.dropSpan()) {
                result.add(outcome.spanData());
            }

            index++;
        }

        if (result != null) {
            return result;
        }

        return snapshotRequired ? ordered : batch;
    }

    private void onExportComplete(CompletableResultCode result,
                                  ExportResponseObservation observation,
                                  long operationSequence) {
        int count = observation.effectiveSpanCount();
        try {
            if (result.isSuccess()) {
                long failures = recordRecoverySuccess(operationSequence);
                if (failures > 0L) {
                    tryPublishRecovery(failures);
                }
                recordAcceptedSpans(observation, count);
            } else {
                recordFailureMetrics(count, operationSequence, ExporterLogEvent.SAFE_EXPORT_RETURNED_FAILURE);
            }
        } catch (Throwable ex) {
            PlatformThrowables.propagateIfFatal(ex);
            diagnosticLog.log(ExporterLogEvent.EXPORT_COMPLETION_CALLBACK_FAILED, fields -> fields
                    .putString(AgentLogField.EXPORTER_TYPE, exporterType)
                    .cause(ex));
        }
    }

    private void recordAcceptedSpans(ExportResponseObservation observation, int count) {
        switch (observation.outcome()) {
            case PARTIALLY_REJECTED -> {
                partialSuccessBatches.increment();
                rejectedSpans.add(observation.rejectedSpans());
                exportedSpans.add(observation.acceptedSpans());
            }

            case NOT_DETERMINED -> {
                acceptedUnknownBatches.increment();
                diagnosticLog.log(ExporterLogEvent.EXPORT_ACCEPTED_SPAN_COUNT_UNAVAILABLE, fields -> fields
                        .putString(AgentLogField.EXPORTER_TYPE, exporterType));
            }

            case ALL_ACCEPTED, NOT_OBSERVED -> exportedSpans.add(count);
        }
    }

    @Override
    public CompletableResultCode flush() {
        try {
            return observeLifecycleResult(delegate.flush(), flushFailures);
        } catch (Throwable ex) {
            PlatformThrowables.propagateIfFatal(ex);
            flushFailures.increment();
            logFlushFailure(ExporterLogEvent.SAFE_FLUSH_THROWN, ex);
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

    @SuppressWarnings("SameParameterValue")
    private void recordSyncFailure(int count,
                                   long operationSequence,
                                   AgentLogEventType eventType) {
        recordSyncFailure(count, operationSequence, eventType, null);
    }

    private void recordSyncFailure(int count,
                                   long operationSequence,
                                   AgentLogEventType eventType,
                                   Throwable error) {
        recordFailureMetrics(count, operationSequence, eventType, error);
    }

    @SuppressWarnings("SameParameterValue")
    private void recordFailureMetrics(int count,
                                      long operationSequence,
                                      AgentLogEventType eventType) {
        recordFailureMetrics(count, operationSequence, eventType, null);
    }

    private void recordFailureMetrics(int count,
                                      long operationSequence,
                                      AgentLogEventType eventType,
                                      Throwable error) {
        recordRecoveryFailure(operationSequence);
        exportFailures.increment();
        droppedSpans.add(count);
        logExportFailure(eventType, error);
    }

    private long nextOperationSequence() {
        synchronized (recoveryLock) {
            return ++operationSeq;
        }
    }

    private void recordRecoveryFailure(long operationSequence) {
        synchronized (recoveryLock) {
            if (!isAfter(operationSequence, lastSuccessSeq)) {
                return;
            }

            if (episodeFailures < Long.MAX_VALUE) {
                episodeFailures++;
            }

            if (isAfter(operationSequence, lastFailureSeq)) {
                lastFailureSeq = operationSequence;
            }
        }
    }

    private long recordRecoverySuccess(long operationSequence) {
        synchronized (recoveryLock) {
            if (isAfter(operationSequence, lastSuccessSeq)) {
                lastSuccessSeq = operationSequence;
            }

            if (episodeFailures == 0L || !isAfter(operationSequence, lastFailureSeq)) {
                return 0L;
            }

            long failures = episodeFailures;
            episodeFailures = 0L;
            return failures;
        }
    }

    private void tryPublishRecovery(long failures) {
        if (!recoveryPublication.tryAcquire()) {
            return;
        }

        LIFECYCLE_LOG.log(ExporterLogEvent.SAFE_EXPORT_SUCCEEDED_AFTER_FAILURES, fields -> fields
                .putString(AgentLogField.EXPORTER_TYPE, exporterType)
                .putLong(AgentLogField.FAILURES, failures));
    }

    private static boolean isAfter(long candidate, long fence) {
        return Long.compareUnsigned(candidate, fence) > 0;
    }

    private void logExportFailure(AgentLogEventType eventType, Throwable error) {
        logFailure(exportLog, eventType, error);
    }

    private void logFlushFailure(AgentLogEventType eventType, Throwable error) {
        logFailure(diagnosticLog, eventType, error);
    }

    private void logFailure(RateLimitedLogger logger, AgentLogEventType eventType, Throwable error) {
        if (error != null) {
            logger.log(eventType, fields -> fields
                    .putString(AgentLogField.EXPORTER_TYPE, exporterType)
                    .cause(error));
        } else {
            logger.log(eventType, fields -> fields.putString(AgentLogField.EXPORTER_TYPE, exporterType));
        }
    }

    private void logLifecycleFailure(AgentLogEventType eventType, Throwable error) {
        if (error != null) {
            LIFECYCLE_LOG.log(eventType, fields -> fields
                    .putString(AgentLogField.EXPORTER_TYPE, exporterType)
                    .cause(error));
        } else {
            LIFECYCLE_LOG.log(eventType, fields -> fields.putString(AgentLogField.EXPORTER_TYPE, exporterType));
        }
    }

    private CompletableResultCode observeLifecycleResult(CompletableResultCode result,
                                                         LongAdder failures) {
        if (result == null) {
            failures.increment();
            logFlushFailure(ExporterLogEvent.SAFE_FLUSH_RETURNED_NULL, null);
            return CompletableResultCode.ofFailure();
        }

        result.whenComplete(() -> {
            if (!result.isSuccess()) {
                failures.increment();
                logFlushFailure(ExporterLogEvent.SAFE_FLUSH_RETURNED_FAILURE, null);
            }
        });
        return result;
    }

    private CompletableResultCode observeShutdownResult(CompletableResultCode result) {
        if (result == null) {
            shutdownFailures.increment();
            logLifecycleFailure(ExporterLogEvent.SAFE_SHUTDOWN_RETURNED_NULL, null);
            return CompletableResultCode.ofFailure();
        }

        result.whenComplete(() -> {
            if (!result.isSuccess()) {
                shutdownFailures.increment();
                logLifecycleFailure(ExporterLogEvent.SAFE_SHUTDOWN_RETURNED_FAILURE, null);
            }
        });
        return result;
    }

    private static String exporterType(SpanExporter delegate) {
        String simpleName = delegate.getClass().getSimpleName();
        return simpleName.isEmpty() ? delegate.getClass().getName() : simpleName;
    }

    public long getExportFailures() {
        return exportFailures.sum();
    }

    public long getDroppedSpans() {
        return droppedSpans.sum();
    }

    public long getExportedSpans() {
        return exportedSpans.sum();
    }

    public long getExportBatches() {
        return exportBatches.sum();
    }

    public long getPartialSuccessBatches() {
        return partialSuccessBatches.sum();
    }

    public long getRejectedSpans() {
        return rejectedSpans.sum();
    }

    public long getAcceptedUnknownBatches() {
        return acceptedUnknownBatches.sum();
    }

    public long getFlushFailures() {
        return flushFailures.sum();
    }

    public long getShutdownFailures() {
        return shutdownFailures.sum();
    }

    public long getGatedSpans() {
        return gatedSpans.sum();
    }

    public Map<String, Long> metricsSnapshot() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        snapshot.put(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORT_BATCHES, getExportBatches());
        snapshot.put(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORT_BATCH_FAILURES, getExportFailures());
        snapshot.put(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORTED_SPANS, getExportedSpans());
        snapshot.put(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_TRANSPORT_DROPPED_SPANS, getDroppedSpans());
        snapshot.put(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_PARTIAL_SUCCESS_BATCHES, getPartialSuccessBatches());
        snapshot.put(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_REJECTED_SPANS, getRejectedSpans());
        snapshot.put(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_ACCEPTED_UNKNOWN_BATCHES, getAcceptedUnknownBatches());
        snapshot.put(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_FLUSH_FAILURES, getFlushFailures());
        snapshot.put(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_SHUTDOWN_FAILURES, getShutdownFailures());
        snapshot.put(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_SUPPRESSED_SPANS_EXPORT_DISABLED, getGatedSpans());

        if (scrubbingRuntime.enabled()) {
            ScrubbingBoundaryMetrics boundaryMetrics = scrubbingRuntime.boundaryMetrics();
            snapshot.put("boundary.input_spans", boundaryMetrics.inputSpans());
            snapshot.put("boundary.output_spans", boundaryMetrics.outputSpans());
            snapshot.put("boundary.spans_changed", boundaryMetrics.spansChanged());
            snapshot.put("boundary.spans_dropped_fail_closed", boundaryMetrics.spansDroppedFailClosed());
            snapshot.put("boundary.events_scrubbed", boundaryMetrics.eventsScrubbed());
            snapshot.put("boundary.links_scrubbed", boundaryMetrics.linksScrubbed());
            snapshot.put("boundary.span_names_scrubbed", boundaryMetrics.spanNamesScrubbed());
            snapshot.put("boundary.exception_events_canonicalized", boundaryMetrics.exceptionEventsCanonicalized());
            snapshot.put("boundary.collapse_failures", boundaryMetrics.collapseFailures());
            snapshot.put("boundary.batch_preparation_failures", boundaryMetrics.batchPreparationFailures());
            snapshot.put("boundary.exporter_invocations", boundaryMetrics.exporterInvocations());
            snapshot.put("boundary.text.hash_downgraded_to_mask", boundaryMetrics.hashDowngradedToMask());
            snapshot.put("boundary.text.truncate_downgraded_to_mask", boundaryMetrics.truncateDowngradedToMask());
            snapshot.put("boundary.critical_failures", boundaryMetrics.criticalFailures());
            snapshot.put("boundary.non_critical_failures", boundaryMetrics.nonCriticalFailures());
            snapshot.put("boundary.skipped_circuit_open", boundaryMetrics.skippedCircuitOpen());
            snapshot.put("boundary.surfaces_collapsed_fail_closed", boundaryMetrics.surfacesCollapsedFailClosed());
            snapshot.put("boundary.value_attributes_removed", boundaryMetrics.valueAttributesRemoved());
            snapshot.put("attributes_verified", scrubbingRuntime.attributesVerified() ? 1L : 0L);
            snapshot.put("collapse_mode_activations", scrubbingRuntime.collapseModeActivations());
            snapshot.put("collapsed_spans", scrubbingRuntime.collapsedSpans());
        }

        return snapshot;
    }
}
