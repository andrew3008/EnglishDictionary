package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

final class DropOldestExportConfig {

    private final int maxQueueSize;
    private final int maxExportBatchSize;
    private final long scheduleDelayNanos;
    private final long exportTimeoutNanos;
    private final long shutdownTimeoutNanos;

    DropOldestExportConfig(int maxQueueSize,
                           int maxExportBatchSize,
                           long scheduleDelayNanos,
                           long exportTimeoutNanos,
                           long shutdownTimeoutNanos) {
        this.maxQueueSize = maxQueueSize;
        this.maxExportBatchSize = maxExportBatchSize;
        this.scheduleDelayNanos = scheduleDelayNanos;
        this.exportTimeoutNanos = exportTimeoutNanos;
        this.shutdownTimeoutNanos = shutdownTimeoutNanos;
    }

    int maxQueueSize() {
        return maxQueueSize;
    }

    int maxExportBatchSize() {
        return maxExportBatchSize;
    }

    long scheduleDelayNanos() {
        return scheduleDelayNanos;
    }

    long exportTimeoutNanos() {
        return exportTimeoutNanos;
    }

    long shutdownTimeoutNanos() {
        return shutdownTimeoutNanos;
    }
}
