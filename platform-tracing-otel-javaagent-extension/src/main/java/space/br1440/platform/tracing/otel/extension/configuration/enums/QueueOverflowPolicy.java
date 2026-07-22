package space.br1440.platform.tracing.otel.extension.configuration.enums;

public enum QueueOverflowPolicy {

    UPSTREAM("UPSTREAM"),
    DROP_OLDEST("DROP_OLDEST");

    private final String value;

    QueueOverflowPolicy(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
