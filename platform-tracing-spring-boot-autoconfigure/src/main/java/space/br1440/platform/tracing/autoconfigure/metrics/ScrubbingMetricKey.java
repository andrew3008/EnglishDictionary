package space.br1440.platform.tracing.autoconfigure.metrics;

enum ScrubbingMetricKey {

    DROP("drop"),
    HASH("hash"),
    MASK("mask"),
    TRUNCATE("truncate"),
    FAILURES("failures"),
    HASH_MISSING_KEY("hash_missing_key"),
    CIRCUIT_BREAKERS_FAILURES_TOTAL("circuit_breakers.failures.total");

    private final String wireKey;

    ScrubbingMetricKey(String wireKey) {
        this.wireKey = wireKey;
    }

    String wireKey() {
        return wireKey;
    }
}
