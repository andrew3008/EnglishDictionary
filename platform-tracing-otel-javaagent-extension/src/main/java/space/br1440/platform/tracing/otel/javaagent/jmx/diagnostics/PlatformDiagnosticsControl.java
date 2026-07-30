package space.br1440.platform.tracing.otel.javaagent.jmx.diagnostics;

import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

import lombok.RequiredArgsConstructor;
import space.br1440.platform.tracing.otel.javaagent.propagation.PlatformPropagationGate;
import space.br1440.platform.tracing.otel.javaagent.safety.ConfigReloadDiagnostics;
import space.br1440.platform.tracing.otel.javaagent.safety.PlatformLogControl;
import space.br1440.platform.tracing.otel.javaagent.safety.TracingDiagnostics;

@RequiredArgsConstructor
public final class PlatformDiagnosticsControl implements PlatformDiagnosticsControlMBean {

    private final LongAdder invalidConfigCounter;

    @Override
    public boolean isPropagationEnabled() {
        return PlatformPropagationGate.shared().isEnabled();
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
    public Map<String, Long> getSafeWrapperMetrics() {
        return TracingDiagnostics.shared().snapshot();
    }

    @Override
    public long getInvalidConfigCount() {
        return invalidConfigCounter.sum();
    }
}
