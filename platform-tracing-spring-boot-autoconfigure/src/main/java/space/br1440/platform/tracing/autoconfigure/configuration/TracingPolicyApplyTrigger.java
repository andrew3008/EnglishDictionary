package space.br1440.platform.tracing.autoconfigure.configuration;

enum TracingPolicyApplyTrigger {
    INITIAL("INITIAL"),
    REFRESH("REFRESH"),
    ACTUATOR("DEVELOPER_COMMAND");

    private final String wireToken;

    TracingPolicyApplyTrigger(String wireToken) {
        this.wireToken = wireToken;
    }

    String wireToken() {
        return wireToken;
    }
}
