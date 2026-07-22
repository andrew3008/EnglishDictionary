package space.br1440.platform.tracing.otel.extension.jmx.scrubbing;

import java.util.Map;

@SuppressWarnings("unused")
public interface PlatformScrubbingControlMBean {

    Map<String, Long> getScrubbingMetrics();

    boolean isScrubbingEnabled();

    void updateScrubbingPolicy(boolean enabled, String[] ruleNames);

    void updateScrubbingPolicy(boolean enabled, String[] ruleNames, String source);

    long getScrubbingConfigVersion();

    String getScrubbingConfigLastUpdatedSource();

}
