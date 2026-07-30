package space.br1440.platform.tracing.otel.javaagent.jmx.sampling;

import java.util.Map;

/**
 * P6.6: platform Sampling MBean is read-only. Mutations go through private {@code applySnapshot}.
 */
@SuppressWarnings("unused")
public interface PlatformSamplingControlMBean {

    boolean isSamplerEnabled();

    Map<String, Double> getRouteRatios();

    double getSamplingRatio();

    long getSamplingConfigVersion();

    String getSamplingConfigLastUpdatedSource();

    long getSamplerDecisionCount(String decision, String reason);

    Map<String, Long> getSamplerDecisionCounts();
}
