package space.br1440.platform.tracing.otel.javaagent.jmx.scrubbing;

import lombok.RequiredArgsConstructor;
import space.br1440.platform.tracing.otel.javaagent.jmx.support.JmxConfigReloadRecorder;
import space.br1440.platform.tracing.otel.javaagent.processor.ScrubbingSpanProcessor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

@RequiredArgsConstructor
public final class PlatformScrubbingControl implements PlatformScrubbingControlMBean {

    private final ScrubbingSpanProcessor scrubbing;
    private final LongAdder invalidConfigCounter;

    @Override
    public Map<String, Long> getScrubbingMetrics() {
        if (scrubbing == null) {
            return Collections.emptyMap();
        }

        Map<String, Long> snapshot = new LinkedHashMap<>();
        snapshot.put("drop", scrubbing.getDroppedActions());
        snapshot.put("hash", scrubbing.getHashedActions());
        snapshot.put("mask", scrubbing.getMaskedActions());
        snapshot.put("truncate", scrubbing.getTruncatedActions());
        snapshot.put("failures", scrubbing.getFailures());
        snapshot.put("hash_missing_key", scrubbing.getHashMissingKey());
        snapshot.put("rules.loaded", scrubbing.getRuleCount());
        snapshot.put("circuit_breakers.open", scrubbing.getOpenBreakerCount());
        snapshot.put("circuit_breakers.failures.total", scrubbing.getTotalBreakerFailures());
        snapshot.put("enabled", scrubbing.isEnabled() ? 1L : 0L);
        snapshot.put("policy.version", scrubbing.getPolicyVersion());

        return snapshot;
    }

    @Override
    public boolean isScrubbingEnabled() {
        return (scrubbing != null) && scrubbing.isEnabled();
    }

    @Override
    public void updateScrubbingPolicy(boolean enabled, String[] ruleNames) {
        updateScrubbingPolicy(enabled, ruleNames, "JMX");
    }

    @Override
    public void updateScrubbingPolicy(boolean enabled, String[] ruleNames, String source) {
        if (scrubbing == null) {
            throw new IllegalStateException("ScrubbingSpanProcessor is not registered");
        }

        try {
            scrubbing.validatePolicyUpdateDomain(ruleNames);
        } catch (IllegalArgumentException e) {
            invalidConfigCounter.increment();
            throw e;
        }

        boolean applied = scrubbing.tryApplyPolicyUpdate(enabled, ruleNames, source);
        if (!applied) {
            invalidConfigCounter.increment();
            JmxConfigReloadRecorder.record("scrubbing", false, scrubbing.getPolicyVersion());
            return;
        }

        JmxConfigReloadRecorder.record("scrubbing", true, scrubbing.getPolicyVersion());
    }

    @Override
    public long getScrubbingConfigVersion() {
        return (scrubbing != null) ? scrubbing.getPolicyVersion() : -1L;
    }

    @Override
    public String getScrubbingConfigLastUpdatedSource() {
        return (scrubbing != null) ? scrubbing.getPolicySource() : "unknown";
    }
}
