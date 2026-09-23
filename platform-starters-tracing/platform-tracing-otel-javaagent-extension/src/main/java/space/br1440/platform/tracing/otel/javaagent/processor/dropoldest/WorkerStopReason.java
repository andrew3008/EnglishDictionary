package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

enum WorkerStopReason {
    DRAIN_COMPLETED,
    SHUTDOWN_FLUSH_FAILED,
    DRAIN_DEADLINE_EXCEEDED,
    WORKER_FAILED
}
