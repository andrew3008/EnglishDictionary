package space.br1440.platform.tracing.otel.extension.jmx.sampling;

import java.util.Map;

@SuppressWarnings("unused")
public interface PlatformSamplingControlMBean {

    boolean isSamplerEnabled();
    void setSamplerEnabled(boolean enabled);

    Map<String, Double> getRouteRatios();
    void setRouteRatios(Map<String, Double> ratios);

    double getSamplingRatio();
    void setSamplingRatio(double newRatio);

    String[] getDropPathPrefixes();
    void setDropPathPrefixes(String[] prefixes);

    String[] getForceRecordValues();
    void setForceRecordValues(String[] values);

    void updateSamplingPolicy(boolean enabled,
                              double ratio,
                              Map<String, Double> routeRatios,
                              String[] dropPaths,
                              String[] forceValues);

    void updateSamplingPolicy(boolean enabled,
                              double defaultRatio,
                              String[] droppedRoutes,
                              String[] forceRecordValues,
                              String[] routeRatioPrefixes,
                              double[] routeRatioValues,
                              String source);

    long getSamplingConfigVersion();

    String getSamplingConfigLastUpdatedSource();

    long getSamplerDecisionCount(String decision, String reason);

    Map<String, Long> getSamplerDecisionCounts();

    void resetSamplerCounters();

}
