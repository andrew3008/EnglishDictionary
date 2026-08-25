package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

enum LifecycleState {
    ACTIVE,
    SHUTTING_DOWN,
    TERMINATED,
    FAILED
}
