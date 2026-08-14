package space.br1440.platform.tracing.autoconfigure.metrics;

enum ScrubbingRuntimeState {

    ACTIVE("active"),
    DEGRADED_FAIL_CLOSED("degraded_fail_closed");

    private final String tag;

    ScrubbingRuntimeState(String tag) {
        this.tag = tag;
    }

    String tag() {
        return tag;
    }
}
