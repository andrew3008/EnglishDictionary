package space.br1440.platform.tracing.otel.extension.exporter;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.tracing.otel.extension.safety.PlatformThrowables;
import space.br1440.platform.tracing.otel.extension.safety.RateLimitedLogger;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

@Slf4j
public final class SafeSpanExporter implements SpanExporter {

    private final SpanExporter delegate;

    private final LongAdder exportBatches = new LongAdder();
    private final LongAdder exportFailures = new LongAdder();
    private final LongAdder exportedSpans = new LongAdder();
    private final LongAdder droppedSpans = new LongAdder();
    private final LongAdder flushFailures = new LongAdder();
    private final LongAdder shutdownFailures = new LongAdder();
    private final AtomicLong lastExportDurationNanos = new AtomicLong();

    private final AtomicBoolean exportEnabled = new AtomicBoolean(true);
    private final LongAdder gatedSpans = new LongAdder();

    private final RateLimitedLogger rateLimitedLog = new RateLimitedLogger(log);

    public SafeSpanExporter(SpanExporter delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public CompletableResultCode export(@Nullable Collection<SpanData> spans) {
        if (spans == null) {
            spans = List.of();
        }

        int count = spans.size();

        if (!exportEnabled.get()) {
            gatedSpans.add(count);
            return CompletableResultCode.ofSuccess();
        }

        exportBatches.increment();
        long startedAt = System.nanoTime();

        CompletableResultCode result;
        try {
            result = delegate.export(spans);
        } catch (Throwable ex) {
            PlatformThrowables.propagateIfFatal(ex);
            recordBatchFailure(count, startedAt, "export() threw: " + ex);
            return CompletableResultCode.ofFailure();
        }

        if (result == null) {
            recordBatchFailure(count, startedAt, "export() returned null");
            return CompletableResultCode.ofFailure();
        }

        result.whenComplete(() -> {
            lastExportDurationNanos.set(System.nanoTime() - startedAt);
            if (result.isSuccess()) {
                exportedSpans.add(count);
                rateLimitedLog.logRecoveryOnce("SafeSpanExporter: export recovered after a series of failures");
            } else {
                exportFailures.increment();
                droppedSpans.add(count);
                logExportFailure("export() returned failure");
            }
        });

        return result;
    }

    @Override
    public CompletableResultCode flush() {
        try {
            CompletableResultCode result = delegate.flush();
            return (result == null) ? CompletableResultCode.ofFailure() : result;
        } catch (Throwable ex) {
            PlatformThrowables.propagateIfFatal(ex);
            flushFailures.increment();
            logExportFailure("flush() threw: " + ex);
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
            logExportFailure("shutdown() threw: " + ex);
            return CompletableResultCode.ofFailure();
        }
    }

    private void recordBatchFailure(int count, long startedAt, String message) {
        exportFailures.increment();
        droppedSpans.add(count);
        lastExportDurationNanos.set(System.nanoTime() - startedAt);
        logExportFailure(message);
    }

    private void logExportFailure(String message) {
        rateLimitedLog.warn("SafeSpanExporter: {}", message);
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
        return exportEnabled.get();
    }

    public void setExportEnabled(boolean enabled) {
        exportEnabled.set(enabled);
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
        snapshot.put(SpanExporterMetrics.FLUSH_FAILURES, getFlushFailures());
        snapshot.put(SpanExporterMetrics.SHUTDOWN_FAILURES, getShutdownFailures());
        snapshot.put(SpanExporterMetrics.LAST_EXPORT_DURATION_NANOS, getLastExportDurationNanos());
        snapshot.put(SpanExporterMetrics.SUPPRESSED_SPANS_EXPORT_DISABLED, getGatedSpans());
        snapshot.put(SpanExporterMetrics.EXPORT_ENABLED, isExportEnabled() ? 1L : 0L);
        return snapshot;
    }
}
