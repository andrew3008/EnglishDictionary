package space.br1440.platform.tracing.otel.extension.jmx.diagnostics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.tracing.otel.extension.jmx.support.JmxConfigReloadRecorder;
import space.br1440.platform.tracing.otel.extension.propagation.PlatformPropagationGate;
import space.br1440.platform.tracing.otel.extension.safety.ConfigReloadDiagnostics;
import space.br1440.platform.tracing.otel.extension.safety.PlatformLogControl;
import space.br1440.platform.tracing.otel.extension.safety.RateLimitedLogger;
import space.br1440.platform.tracing.otel.extension.safety.TracingDiagnostics;

import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

@Slf4j
@RequiredArgsConstructor
public final class PlatformDiagnosticsControl implements PlatformDiagnosticsControlMBean {

    private final LongAdder invalidConfigCounter;

    private final RateLimitedLogger logLevelChangeLog = new RateLimitedLogger(log);

    @Override
    public boolean isPropagationEnabled() {
        return PlatformPropagationGate.shared().isEnabled();
    }

    @Override
    public void setPropagationEnabled(boolean enabled) {
        PlatformPropagationGate.shared().setEnabled(enabled);
        JmxConfigReloadRecorder.record("propagation", true, -1L);
    }

    @Override
    public Map<String, Long> getConfigReloadMetrics() {
        return ConfigReloadDiagnostics.shared().snapshot();
    }

    @Override
    public String[] getConfigAuditTrail() {
        return ConfigReloadDiagnostics.shared().auditTrail();
    }

    @Override
    public String getPlatformLogLevel() {
        return PlatformLogControl.shared().getLevel().name();
    }

    @Override
    public void setPlatformLogLevel(String level) {
        boolean applied = PlatformLogControl.shared().setLevel(level);
        if (!applied) {
            invalidConfigCounter.increment();
            JmxConfigReloadRecorder.record("log_level", false, -1L);
            throw new IllegalArgumentException("Unknown log level: " + level + " (expected OFF/ERROR/WARN/INFO/DEBUG/TRACE)");
        }

        JmxConfigReloadRecorder.record("log_level", true, -1L);
        logLevelChangeLog.warn("Platform log level изменён через JMX: {}", PlatformLogControl.shared().getLevel());
    }

    @Override
    public Map<String, Long> getSafeWrapperMetrics() {
        return TracingDiagnostics.shared().snapshot();
    }

    @Override
    public long getInvalidConfigCount() {
        return invalidConfigCounter.sum();
    }
}
