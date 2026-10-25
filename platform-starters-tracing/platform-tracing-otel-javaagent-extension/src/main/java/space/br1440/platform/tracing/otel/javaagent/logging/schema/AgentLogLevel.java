package space.br1440.platform.tracing.otel.javaagent.logging.schema;

public enum AgentLogLevel {

    OFF(-1, false),
    ERROR(0, true),
    WARN(1, true),
    INFO(2, true),
    DEBUG(3, true),
    TRACE(4, true);

    private final int detail;
    private final boolean eventLevel;

    AgentLogLevel(int detail, boolean eventLevel) {
        this.detail = detail;
        this.eventLevel = eventLevel;
    }

    public boolean permits(AgentLogLevel eventLevel) {
        if (eventLevel == null || !eventLevel.eventLevel) {
            return false;
        }

        return (this != OFF) && (eventLevel.detail <= detail);
    }

    @SuppressWarnings("UnusedReturnValue")
    public AgentLogLevel requireEventLevel() {
        if (!eventLevel) {
            throw new IllegalArgumentException(name() + " is not a valid event level");
        }

        return this;
    }
}
