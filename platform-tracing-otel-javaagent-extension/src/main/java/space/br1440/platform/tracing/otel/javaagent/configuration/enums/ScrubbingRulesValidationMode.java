package space.br1440.platform.tracing.otel.javaagent.configuration.enums;

public enum ScrubbingRulesValidationMode {

    LENIENT("LENIENT"),
    STRICT("STRICT");

    private final String value;

    ScrubbingRulesValidationMode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
