package space.br1440.platform.tracing.otel.javaagent.jmx.diagnostics;

import java.util.Map;

/**
 * P6.6: platform Diagnostics MBean is read-only. Mutations go through private {@code applySnapshot}.
 */
@SuppressWarnings("unused")
public interface PlatformDiagnosticsControlMBean {

    boolean isPropagationEnabled();

    Map<String, Long> getConfigReloadMetrics();

    String[] getConfigAuditTrail();

    String getPlatformLogLevel();

    Map<String, Long> getSafeWrapperMetrics();

    long getInvalidConfigCount();
}
