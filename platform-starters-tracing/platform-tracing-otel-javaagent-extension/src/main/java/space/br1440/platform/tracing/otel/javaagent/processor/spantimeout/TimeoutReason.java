package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

enum TimeoutReason {
    SPAN("span"),
    TRACE("trace");

    private final String value;

    TimeoutReason(String value) {
        this.value = value;
    }

    String value() {
        return value;
    }
}
