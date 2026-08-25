package space.br1440.platform.tracing.otel.javaagent.factory;

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
