package space.br1440.platform.tracing.otel.javaagent.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;

public final class TracingDiagnostics {

    private static final TracingDiagnostics SHARED = new TracingDiagnostics();

    private final LongAdder samplerFailures = new LongAdder();
    private final LongAdder propagatorFailures = new LongAdder();
    private final LongAdder resourceFailures = new LongAdder();
    private final LongAdder suppressedErrors = new LongAdder();

    private final AtomicBoolean degradedModeEnabled = new AtomicBoolean(false);
    private final AtomicLong lastFailureEpochMs = new AtomicLong(0L);

    public static TracingDiagnostics shared() {
        return SHARED;
    }

    public void recordSamplerFailure() {
        samplerFailures.increment();
        onAnyFailure();
    }

    public void recordPropagatorFailure() {
        propagatorFailures.increment();
        onAnyFailure();
    }

    public void recordResourceFailure() {
        resourceFailures.increment();
        onAnyFailure();
    }

    private void onAnyFailure() {
        suppressedErrors.increment();
        lastFailureEpochMs.set(System.currentTimeMillis());
    }

    public void setDegradedMode(boolean enabled) {
        degradedModeEnabled.set(enabled);
    }

    public boolean isDegradedMode() {
        return degradedModeEnabled.get();
    }

    public long getSamplerFailures() {
        return samplerFailures.sum();
    }

    public long getPropagatorFailures() {
        return propagatorFailures.sum();
    }

    public long getResourceFailures() {
        return resourceFailures.sum();
    }

    public long getSuppressedErrors() {
        return suppressedErrors.sum();
    }

    public long getLastFailureEpochMs() {
        return lastFailureEpochMs.get();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        snapshot.put(JmxWireContract.KEY_SAFE_WRAPPER_METRICS_SAMPLER_FAILURES, getSamplerFailures());
        snapshot.put(JmxWireContract.KEY_SAFE_WRAPPER_METRICS_PROPAGATOR_FAILURES, getPropagatorFailures());
        snapshot.put(JmxWireContract.KEY_SAFE_WRAPPER_METRICS_RESOURCE_FAILURES, getResourceFailures());
        snapshot.put(JmxWireContract.KEY_SAFE_WRAPPER_METRICS_SAFE_WRAPPER_SUPPRESSED_ERRORS, getSuppressedErrors());
        snapshot.put(JmxWireContract.KEY_SAFE_WRAPPER_METRICS_DEGRADED_MODE_ENABLED, degradedModeEnabled.get() ? 1L : 0L);
        snapshot.put(JmxWireContract.KEY_SAFE_WRAPPER_METRICS_LAST_FAILURE_EPOCH_MS, getLastFailureEpochMs());
        return snapshot;
    }
}
