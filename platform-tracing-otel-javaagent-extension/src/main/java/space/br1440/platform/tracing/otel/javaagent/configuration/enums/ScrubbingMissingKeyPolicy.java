package space.br1440.platform.tracing.otel.javaagent.configuration.enums;

public enum ScrubbingMissingKeyPolicy {

    MASK("mask"),
    FAIL_FAST("fail-fast");

    private final String value;

    ScrubbingMissingKeyPolicy(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
