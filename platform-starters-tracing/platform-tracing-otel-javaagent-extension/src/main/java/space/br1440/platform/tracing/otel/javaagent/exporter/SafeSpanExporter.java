package space.br1440.platform.tracing.otel.javaagent.exporter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseObservation;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseScope;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;
import space.br1440.platform.tracing.otel.javaagent.resilience.RateLimitedLogger;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingBoundaryMetrics;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingFailureLog;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

public final class SafeSpanExporter implements SpanExporter {

    private static final RateLimitedLogger log = new RateLimitedLogger(SafeSpanExporter.class);

    private final SpanExporter delegate;
    private final TracingSettingsSource acceptedPolicy;
    private final ScrubbingRuntime scrubbingRuntime;
    private final SpanDataBoundaryScrubber boundaryScrubber;

    private final LongAdder exportBatches = new LongAdder();
    private final LongAdder exportFailures = new LongAdder();
    private final LongAdder exportedSpans = new LongAdder();
    private final LongAdder droppedSpans = new LongAdder();
    private final LongAdder partialSuccessBatches = new LongAdder();
    private final LongAdder rejectedSpans = new LongAdder();
    private final LongAdder acceptedUnknownBatches = new LongAdder();
    private final LongAdder flushFailures = new LongAdder();
    private final LongAdder shutdownFailures = new LongAdder();
    private final AtomicLong lastExportDurationNanos = new AtomicLong();

    private final LongAdder gatedSpans = new LongAdder();

    public SafeSpanExporter(SpanExporter delegate, TracingSettingsSource acceptedPolicy, ScrubbingRuntime scrubbingRuntime) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.acceptedPolicy = Objects.requireNonNull(acceptedPolicy, "acceptedPolicy");
        this.scrubbingRuntime = Objects.requireNonNull(scrubbingRuntime, "scrubbingRuntime");
        this.boundaryScrubber = new SpanDataBoundaryScrubber(this.scrubbingRuntime);
    }

    public boolean isProtectedBy(ScrubbingRuntime expectedRuntime) {
        return this.scrubbingRuntime == expectedRuntime;
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
        long startedAt = System.nanoTime();

        ExportResponseObservation observation = new ExportResponseObservation(effectiveCount);
        CompletableResultCode result;
        try (ExportResponseScope.Handle ignored = ExportResponseScope.open(observation)) {
            result = delegate.export(batch);
        } catch (Throwable ex) {
            PlatformThrowables.propagateIfFatal(ex);
            recordSyncFailure(effectiveCount, startedAt, "export() threw", ex);
            return CompletableResultCode.ofFailure();
        }

        if (result == null) {
            recordSyncFailure(effectiveCount, startedAt, "export() returned null");
            return CompletableResultCode.ofFailure();
        }

        result.whenComplete(() -> onExportComplete(result, observation, startedAt));
        return result;
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
                                  long startedAt) {
        int count = observation.effectiveSpanCount();
        try {
            lastExportDurationNanos.set(System.nanoTime() - startedAt);

            if (result.isSuccess()) {
                recordAcceptedSpans(observation, count);
                log.logRecoveryOnce("SafeSpanExporter: export recovered after a series of failures");
            } else {
                recordFailureMetrics(count, "export() returned failure");
            }
        } catch (Throwable ex) {
            PlatformThrowables.propagateIfFatal(ex);
            log.warn(AgentEventCode.EXPORT_SAFE_EXPORTER, "completion callback failed", ex);
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
                log.warn(AgentEventCode.EXPORT_SAFE_EXPORTER, "OTLP response did not yield a usable accepted span count");
            }

            case ALL_ACCEPTED, NOT_OBSERVED -> exportedSpans.add(count);
        }
    }

    @Override
    public CompletableResultCode flush() {
        try {
            CompletableResultCode result = delegate.flush();
            return (result == null) ? CompletableResultCode.ofFailure() : result;
        } catch (Throwable ex) {
            PlatformThrowables.propagateIfFatal(ex);
            flushFailures.increment();
            logExportFailure("flush() threw", ex);
            return CompletableResultCode.ofFailure();
        }
    }

    @Override
    public CompletableResultCode shutdown() {
        try {
            CompletableResultCode result = delegate.shutdown();
            return (result == null) ? CompletableResultCode.ofFailure() : result;
        } catch (Throwable ex) {
            PlatformThrowables.propagateIfFatal(ex);
            shutdownFailures.increment();
            logExportFailure("shutdown() threw", ex);
            return CompletableResultCode.ofFailure();
        }
    }

    private void recordBoundaryBatchPreparationFailure(int originalCount, Throwable failure) {
        scrubbingRuntime.recordBoundaryBatchPreparationFailure();
        if (originalCount > 0) {
            scrubbingRuntime.recordBoundarySpansDroppedFailClosed(originalCount);
        }

        ScrubbingFailureLog.error(log, "BOUNDARY_BATCH_PREPARATION_FAILED", failure);
    }

    @SuppressWarnings("SameParameterValue")
    private void recordSyncFailure(int count, long startedAt, String message) {
        recordSyncFailure(count, startedAt, message, null);
    }

    private void recordSyncFailure(int count, long startedAt, String message, Throwable error) {
        lastExportDurationNanos.set(System.nanoTime() - startedAt);
        recordFailureMetrics(count, message, error);
    }

    @SuppressWarnings("SameParameterValue")
    private void recordFailureMetrics(int count, String message) {
        recordFailureMetrics(count, message, null);
    }

    private void recordFailureMetrics(int count, String message, Throwable error) {
        exportFailures.increment();
        droppedSpans.add(count);
        logExportFailure(message, error);
    }

    private void logExportFailure(String message, Throwable error) {
        if (error != null) {
            log.warn(AgentEventCode.EXPORT_SAFE_EXPORTER, message, error);
        } else {
            log.warn(AgentEventCode.EXPORT_SAFE_EXPORTER, message);
        }
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

    public long getLastExportDurationNanos() {
        return lastExportDurationNanos.get();
    }

    public boolean isExportEnabled() {
        return acceptedPolicy.current().exporterEnabled();
    }

    public long getGatedSpans() {
        return gatedSpans.sum();
    }

    public Map<String, Long> metricsSnapshot() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        snapshot.put(SpanExporterMetrics.EXPORT_BATCHES, getExportBatches());
        snapshot.put(SpanExporterMetrics.EXPORT_BATCH_FAILURES, getExportFailures());
        snapshot.put(SpanExporterMetrics.EXPORTED_SPANS, getExportedSpans());
        snapshot.put(SpanExporterMetrics.TRANSPORT_DROPPED_SPANS, getDroppedSpans());
        snapshot.put(SpanExporterMetrics.PARTIAL_SUCCESS_BATCHES, getPartialSuccessBatches());
        snapshot.put(SpanExporterMetrics.REJECTED_SPANS, getRejectedSpans());
        snapshot.put(SpanExporterMetrics.ACCEPTED_UNKNOWN_BATCHES, getAcceptedUnknownBatches());
        snapshot.put(SpanExporterMetrics.FLUSH_FAILURES, getFlushFailures());
        snapshot.put(SpanExporterMetrics.SHUTDOWN_FAILURES, getShutdownFailures());
        snapshot.put(SpanExporterMetrics.LAST_EXPORT_DURATION_NANOS, getLastExportDurationNanos());
        snapshot.put(SpanExporterMetrics.SUPPRESSED_SPANS_EXPORT_DISABLED, getGatedSpans());
        snapshot.put(SpanExporterMetrics.EXPORT_ENABLED, isExportEnabled() ? 1L : 0L);

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
            snapshot.put("attributes_verified", scrubbingRuntime.attributesVerified() ? 1L : 0L);
            snapshot.put("collapse_mode_activations", scrubbingRuntime.collapseModeActivations());
            snapshot.put("collapsed_spans", scrubbingRuntime.collapsedSpans());
        }

        return snapshot;
    }
}
