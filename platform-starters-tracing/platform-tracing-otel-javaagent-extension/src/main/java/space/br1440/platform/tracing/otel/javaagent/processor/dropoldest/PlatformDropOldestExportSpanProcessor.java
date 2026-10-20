package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import java.time.Duration;
import java.util.Objects;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults;
import space.br1440.platform.tracing.otel.javaagent.configuration.OtelSdkDefaults;
import space.br1440.platform.tracing.otel.javaagent.logging.RateLimitedLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ExportPipelineLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import lombok.NonNull;

public final class PlatformDropOldestExportSpanProcessor implements SpanProcessor {

    private static final RateLimitedLogger log = new RateLimitedLogger(PlatformDropOldestExportSpanProcessor.class);

    private final int queueCapacity;
    private final DropOldestExportMetrics metrics;
    private final ExportBuffer buffer;
    private final ExportWorker worker;

    private PlatformDropOldestExportSpanProcessor(PreparedBuild preparedBuild) {
        this.queueCapacity = preparedBuild.maxQueueSize;
        this.metrics = new DropOldestExportMetrics();
        this.buffer = new ExportBuffer(
                preparedBuild.maxQueueSize,
                preparedBuild.maxExportBatchSize,
                metrics
        );

        TimedSpanExporter timedSpanExporter = new TimedSpanExporter(
                preparedBuild.exporter,
                preparedBuild.exportTimeout.toNanos(),
                preparedBuild.shutdownTimeout.toNanos(),
                metrics
        );

        this.worker = new ExportWorker(
                buffer,
                timedSpanExporter,
                preparedBuild.scheduleDelay.toNanos(),
                preparedBuild.shutdownTimeout.toNanos()
        );
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
            log.log(ExportPipelineLogEvent.SPAN_TO_DATA_CONVERSION_FAILED, fields -> fields.cause(e));
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
        return worker.shutdown();
    }

    public MetricsSnapshot metricsSnapshot() {
        ExportBuffer.BufferSnapshot bufferSnapshot = buffer.snapshot();
        return new MetricsSnapshot(
                queueCapacity,
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

    public static Builder builder(SpanExporter exporter) {
        return new Builder(exporter);
    }

    public static final class Builder {

        private final SpanExporter exporter;
        private int maxQueueSize = OtelSdkDefaults.DEFAULT_BSP_MAX_QUEUE_SIZE;
        private int maxExportBatchSize = OtelSdkDefaults.DEFAULT_BSP_MAX_EXPORT_BATCH_SIZE;
        private Duration scheduleDelay = OtelSdkDefaults.DEFAULT_BSP_SCHEDULE_DELAY;
        private Duration exportTimeout = OtelSdkDefaults.DEFAULT_BSP_EXPORT_TIMEOUT;
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
            return validate().build();
        }

        public PreparedBuild validate() {
            if (maxQueueSize <= 0) {
                throw new IllegalArgumentException("maxQueueSize must be positive");
            }

            if (maxExportBatchSize <= 0) {
                throw new IllegalArgumentException("maxExportBatchSize must be positive");
            }

            if (maxExportBatchSize > maxQueueSize) {
                log.log(ExportPipelineLogEvent.MAX_EXPORT_BATCH_SIZE_CLAMPED,
                        fields -> fields.putString(AgentLogField.NAME, "maxExportBatchSize"));

                maxExportBatchSize = maxQueueSize;
            }

            if (scheduleDelay == null || scheduleDelay.isNegative() || scheduleDelay.isZero()) {
                throw new IllegalArgumentException("scheduleDelay must be positive");
            }

            if (exportTimeout == null || exportTimeout.isNegative() || exportTimeout.isZero()) {
                throw new IllegalArgumentException("exportTimeout must be positive");
            }

            if (shutdownTimeout == null || shutdownTimeout.isNegative() || shutdownTimeout.isZero()) {
                throw new IllegalArgumentException("shutdownTimeout must be positive");
            }

            return new PreparedBuild(this);
        }
    }

    public static final class PreparedBuild {

        private final SpanExporter exporter;
        private final int maxQueueSize;
        private final int maxExportBatchSize;
        private final Duration scheduleDelay;
        private final Duration exportTimeout;
        private final Duration shutdownTimeout;

        private PreparedBuild(Builder builder) {
            this.exporter = builder.exporter;
            this.maxQueueSize = builder.maxQueueSize;
            this.maxExportBatchSize = builder.maxExportBatchSize;
            this.scheduleDelay = builder.scheduleDelay;
            this.exportTimeout = builder.exportTimeout;
            this.shutdownTimeout = builder.shutdownTimeout;
        }

        public PlatformDropOldestExportSpanProcessor build() {
            return new PlatformDropOldestExportSpanProcessor(this);
        }
    }
}
