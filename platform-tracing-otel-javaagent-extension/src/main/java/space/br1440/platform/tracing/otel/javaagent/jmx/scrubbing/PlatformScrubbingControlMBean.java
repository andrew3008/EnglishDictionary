package space.br1440.platform.tracing.otel.javaagent.jmx.scrubbing;

import java.util.Map;

/**
 * P6.6: platform Scrubbing MBean mutations deleted. Category B holder removal is P6.7.
 */
@SuppressWarnings("unused")
public interface PlatformScrubbingControlMBean {

    Map<String, Long> getScrubbingMetrics();

    boolean isScrubbingEnabled();

    long getScrubbingConfigVersion();

    String getScrubbingConfigLastUpdatedSource();
}
