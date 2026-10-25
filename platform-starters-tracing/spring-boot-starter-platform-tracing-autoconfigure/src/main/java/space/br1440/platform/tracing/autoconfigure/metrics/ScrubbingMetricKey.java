package space.br1440.platform.tracing.autoconfigure.metrics;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

enum ScrubbingMetricKey {

    DROP(JmxWireContract.KEY_SCRUBBING_METRICS_DROP),
    HASH(JmxWireContract.KEY_SCRUBBING_METRICS_HASH),
    MASK(JmxWireContract.KEY_SCRUBBING_METRICS_MASK),
    TRUNCATE(JmxWireContract.KEY_SCRUBBING_METRICS_TRUNCATE),
    FAILURES(JmxWireContract.KEY_SCRUBBING_METRICS_FAILURES),
    HASH_MISSING_KEY(JmxWireContract.KEY_SCRUBBING_METRICS_HASH_MISSING_KEY),
    CIRCUIT_BREAKERS_FAILURES_TOTAL(JmxWireContract.KEY_SCRUBBING_METRICS_CIRCUIT_BREAKERS_FAILURES_TOTAL),
    SKIPPED_CIRCUIT_OPEN(JmxWireContract.KEY_SCRUBBING_METRICS_SKIPPED_CIRCUIT_OPEN),
    COLLAPSE_MODE_ACTIVATIONS(JmxWireContract.KEY_SCRUBBING_METRICS_COLLAPSE_MODE_ACTIVATIONS),
    COLLAPSED_SPANS(JmxWireContract.KEY_SCRUBBING_METRICS_COLLAPSED_SPANS);

    private final String wireKey;

    ScrubbingMetricKey(String wireKey) {
        this.wireKey = wireKey;
    }

    String wireKey() {
        return wireKey;
    }
}
