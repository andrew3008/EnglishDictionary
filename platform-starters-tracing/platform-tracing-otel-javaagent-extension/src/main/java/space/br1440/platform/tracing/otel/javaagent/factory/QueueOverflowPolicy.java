package space.br1440.platform.tracing.otel.javaagent.factory;

/**
 * Overflow policy for the platform export queue, consumed exclusively by
 * {@link PlatformExportProcessorFactory} when selecting the {@code BatchSpanProcessor}
 * versus the drop-oldest replacement.
 */
enum QueueOverflowPolicy {

    UPSTREAM("UPSTREAM"),
    DROP_OLDEST("DROP_OLDEST");

    private final String value;

    QueueOverflowPolicy(String value) {
        this.value = value;
    }

    String value() {
        return value;
    }
}
