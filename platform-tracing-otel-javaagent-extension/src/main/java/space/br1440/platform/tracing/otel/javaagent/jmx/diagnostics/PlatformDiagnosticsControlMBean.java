package space.br1440.platform.tracing.otel.javaagent.jmx.diagnostics;

import java.util.Map;

@SuppressWarnings("unused")
public interface PlatformDiagnosticsControlMBean {

    boolean isPropagationEnabled();

    void setPropagationEnabled(boolean enabled);

    Map<String, Long> getConfigReloadMetrics();

    String[] getConfigAuditTrail();

    String getPlatformLogLevel();

    void setPlatformLogLevel(String level);

    Map<String, Long> getSafeWrapperMetrics();

    long getInvalidConfigCount();

}
