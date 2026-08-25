package space.br1440.platform.tracing.otel.javaagent.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class TracingDiagnostics {

    private static final TracingDiagnostics SHARED = new TracingDiagnostics();

    private final LongAdder samplerFailures = new LongAdder();
    private final LongAdder propagatorFailures = new LongAdder();
    private final LongAdder resourceFailures = new LongAdder();
    private final LongAdder scopeFailures = new LongAdder();
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

    public void recordScopeFailure() {
        scopeFailures.increment();
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

    public long getScopeFailures() {
        return scopeFailures.sum();
    }

    public long getSuppressedErrors() {
        return suppressedErrors.sum();
    }

    public long getLastFailureEpochMs() {
        return lastFailureEpochMs.get();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        snapshot.put("sampler.failures", getSamplerFailures());
        snapshot.put("propagator.failures", getPropagatorFailures());
        snapshot.put("resource.failures", getResourceFailures());
        snapshot.put("scope.failures", getScopeFailures());
        snapshot.put("safe_wrapper.suppressed_errors", getSuppressedErrors());
        snapshot.put("degraded_mode.enabled", degradedModeEnabled.get() ? 1L : 0L);
        snapshot.put("last_failure.epoch_ms", getLastFailureEpochMs());
        return snapshot;
    }
}
