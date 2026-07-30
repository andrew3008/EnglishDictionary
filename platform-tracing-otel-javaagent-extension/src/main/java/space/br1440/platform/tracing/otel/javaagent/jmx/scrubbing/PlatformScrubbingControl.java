package space.br1440.platform.tracing.otel.javaagent.jmx.scrubbing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import space.br1440.platform.tracing.otel.javaagent.processor.ScrubbingSpanProcessor;

@RequiredArgsConstructor
public final class PlatformScrubbingControl implements PlatformScrubbingControlMBean {

    private final ScrubbingSpanProcessor scrubbing;

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
    public long getScrubbingConfigVersion() {
        return (scrubbing != null) ? scrubbing.getPolicyVersion() : -1L;
    }

    @Override
    public String getScrubbingConfigLastUpdatedSource() {
        return (scrubbing != null) ? scrubbing.getPolicySource() : "unknown";
    }
}
