package space.br1440.platform.tracing.otel.javaagent.bootstrap;

enum BootstrapState {
    NEW,
    PREPARING,
    PREPARED,
    ACTIVATING,
    ACTIVE,
    CLOSING,
    CLOSED,
    FAILED
}
