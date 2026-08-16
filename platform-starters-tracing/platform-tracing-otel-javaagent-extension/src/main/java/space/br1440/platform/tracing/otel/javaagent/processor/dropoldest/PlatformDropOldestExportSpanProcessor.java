package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import java.time.Duration;
import java.util.Objects;
import jakarta.annotation.Nonnull;

import org.slf4j.LoggerFactory;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults;
import space.br1440.platform.tracing.otel.javaagent.resilience.RateLimitedLogger;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import lombok.NonNull;

/**
 * DROP_OLDEST {@link SpanProcessor}: неблокирующий bounded queue + отдельный
 * export worker, вытесняющий самые старые спаны при переполнении очереди вместо
 * блокировки вызывающего потока (в отличие от stock {@code BatchSpanProcessor}).
 */
public final class PlatformDropOldestExportSpanProcessor implements SpanProcessor {

    private static final RateLimitedLogger log = new RateLimitedLogger(
            LoggerFactory.getLogger(PlatformDropOldestExportSpanProcessor.class)
    );

    private final DropOldestExportConfig config;
    private final DropOldestExportMetrics metrics;
    private final ExportBuffer buffer;
    private final ProcessorLifecycle lifecycle;

    private PlatformDropOldestExportSpanProcessor(Builder builder) {
        this.config = new DropOldestExportConfig(
                builder.maxQueueSize,
                builder.maxExportBatchSize,
                builder.scheduleDelay.toNanos(),
                builder.exportTimeout.toNanos(),
                builder.shutdownTimeout.toNanos()
        );

        this.metrics = new DropOldestExportMetrics();
        this.buffer = new ExportBuffer(config.maxQueueSize(), config.maxExportBatchSize(), metrics);

        TimedSpanExporter timedSpanExporter = new TimedSpanExporter(
                builder.exporter, config.exportTimeoutNanos(), config.shutdownTimeoutNanos(), metrics
        );

        ExportWorker worker = new ExportWorker(buffer, timedSpanExporter, config.scheduleDelayNanos());
        this.lifecycle = new ProcessorLifecycle(buffer, worker, timedSpanExporter, config.shutdownTimeoutNanos());
        worker.start();
    }

    @Override
    public void onStart(@NonNull Context parentContext, @NonNull ReadWriteSpan span) {
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public void onEnd(@Nonnull ReadableSpan span) {
        if (!span.getSpanContext().isSampled()) {
            return;
        }

        if (!buffer.isAdmissionOpen()) {
            buffer.rejectAdmission();
            return;
        }

        SpanData snapshot;
        try {
            snapshot = span.toSpanData();
        } catch (RuntimeException e) {
            log.warn("toSpanData() failed: {}", e.getMessage());
            return;
        }

        buffer.admit(snapshot);
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public CompletableResultCode forceFlush() {
        return buffer.requestFlush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return lifecycle.shutdown();
    }

    public MetricsSnapshot metricsSnapshot() {
        ExportBuffer.BufferSnapshot bufferSnapshot = buffer.snapshot();
        return new MetricsSnapshot(
                config.maxQueueSize(),
                bufferSnapshot.queueSize(),
                bufferSnapshot.lifecycleState().name(),
                metrics.droppedOverflowTotal(),
                metrics.droppedAfterShutdownTotal(),
                metrics.droppedAfterFailureTotal(),
                metrics.discardedQueuedTotal(),
                metrics.failuresTotal(),
                metrics.shutdownFailuresTotal(),
                metrics.timeoutsTotal()
        );
    }

    public record MetricsSnapshot(
            int queueCapacity,
            int queueSize,
            String lifecycleState,
            long droppedOverflowTotal,
            long droppedAfterShutdownTotal,
            long droppedAfterFailureTotal,
            long discardedQueuedTotal,
            long failuresTotal,
            long shutdownFailuresTotal,
            long timeoutsTotal) {
    }

    // Test-only диагностический хук
    int pendingFlushGenerationCount() {
        return buffer.pendingFlushGenerationCount();
    }

    public static Builder builder(SpanExporter exporter) {
        return new Builder(exporter);
    }

    public static final class Builder {

        private static final int DEFAULT_MAX_QUEUE_SIZE = 2048;
        private static final int DEFAULT_MAX_EXPORT_BATCH_SIZE = 512;
        private static final Duration DEFAULT_SCHEDULE_DELAY = Duration.ofMillis(5_000);
        private static final Duration DEFAULT_EXPORT_TIMEOUT = Duration.ofMillis(5_000);

        private final SpanExporter exporter;
        private int maxQueueSize = DEFAULT_MAX_QUEUE_SIZE;
        private int maxExportBatchSize = DEFAULT_MAX_EXPORT_BATCH_SIZE;
        private Duration scheduleDelay = DEFAULT_SCHEDULE_DELAY;
        private Duration exportTimeout = DEFAULT_EXPORT_TIMEOUT;
        private Duration shutdownTimeout = ExtensionDefaults.DEFAULT_DROP_OLDEST_SHUTDOWN_TIMEOUT;

        private Builder(SpanExporter exporter) {
            this.exporter = Objects.requireNonNull(exporter, "exporter");
        }

        public Builder maxQueueSize(int maxQueueSize) {
            this.maxQueueSize = maxQueueSize;
            return this;
        }

        public Builder maxExportBatchSize(int maxExportBatchSize) {
            this.maxExportBatchSize = maxExportBatchSize;
            return this;
        }

        public Builder scheduleDelay(Duration scheduleDelay) {
            this.scheduleDelay = scheduleDelay;
            return this;
        }

        public Builder exportTimeout(Duration exportTimeout) {
            this.exportTimeout = exportTimeout;
            return this;
        }

        public Builder shutdownTimeout(Duration shutdownTimeout) {
            this.shutdownTimeout = shutdownTimeout;
            return this;
        }

        public Builder readBspConfigFrom(ConfigProperties config) {
            Objects.requireNonNull(config, "config");

            Integer mqs = config.getInt("otel.bsp.max.queue.size");
            if (mqs != null) {
                this.maxQueueSize = mqs;
            }

            Integer mebs = config.getInt("otel.bsp.max.export.batch.size");
            if (mebs != null) {
                this.maxExportBatchSize = mebs;
            }

            Duration sd = config.getDuration("otel.bsp.schedule.delay");
            if (sd != null) {
                this.scheduleDelay = sd;
            }

            Duration et = config.getDuration("otel.bsp.export.timeout");
            if (et != null) {
                this.exportTimeout = et;
            }

            return this;
        }

        public PlatformDropOldestExportSpanProcessor build() {
            applyValidationWithSafeFallback();
            return new PlatformDropOldestExportSpanProcessor(this);
        }

        private void applyValidationWithSafeFallback() {
            if (maxQueueSize <= 0) {
                log.warn("PlatformDropOldestExportSpanProcessor.builder: maxQueueSize={} is invalid, fallback={}",
                        maxQueueSize, DEFAULT_MAX_QUEUE_SIZE);
                maxQueueSize = DEFAULT_MAX_QUEUE_SIZE;
            }

            if (maxExportBatchSize <= 0) {
                log.warn("PlatformDropOldestExportSpanProcessor.builder: maxExportBatchSize={} is invalid, fallback={}",
                        maxExportBatchSize, DEFAULT_MAX_EXPORT_BATCH_SIZE);
                maxExportBatchSize = DEFAULT_MAX_EXPORT_BATCH_SIZE;
            }

            if (maxExportBatchSize > maxQueueSize) {
                log.warn("PlatformDropOldestExportSpanProcessor.builder: maxExportBatchSize={} > maxQueueSize={}, "
                                + "fallback maxExportBatchSize={}",
                        maxExportBatchSize, maxQueueSize, maxQueueSize);
                maxExportBatchSize = maxQueueSize;
            }

            if (scheduleDelay == null || scheduleDelay.isNegative() || scheduleDelay.isZero()) {
                log.warn("PlatformDropOldestExportSpanProcessor.builder: scheduleDelay={} is invalid, fallback={}",
                        scheduleDelay, DEFAULT_SCHEDULE_DELAY);
                scheduleDelay = DEFAULT_SCHEDULE_DELAY;
            }

            if (exportTimeout == null || exportTimeout.isNegative() || exportTimeout.isZero()) {
                log.warn("PlatformDropOldestExportSpanProcessor.builder: exportTimeout={} is invalid, fallback={}",
                        exportTimeout, DEFAULT_EXPORT_TIMEOUT);
                exportTimeout = DEFAULT_EXPORT_TIMEOUT;
            }

            if (shutdownTimeout == null || shutdownTimeout.isNegative() || shutdownTimeout.isZero()) {
                log.warn("PlatformDropOldestExportSpanProcessor.builder: shutdownTimeout={} is invalid, fallback={}",
                        shutdownTimeout, ExtensionDefaults.DEFAULT_DROP_OLDEST_SHUTDOWN_TIMEOUT);
                shutdownTimeout = ExtensionDefaults.DEFAULT_DROP_OLDEST_SHUTDOWN_TIMEOUT;
            }
        }
    }
}
