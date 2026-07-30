package space.br1440.platform.tracing.autoconfigure.sampling;

import space.br1440.platform.tracing.autoconfigure.TracingProperties;

import java.util.Objects;

/**
 * Spring-side sampling runtime config for Category A reconcile (enabled / ratio / route-ratios).
 * <p>
 * Category B startup-only leaves ({@code drop-paths}, {@code force-record-values}) are owned by
 * the Agent under {@code platform.tracing.javaagent.sampling.*}. Runtime mutation of Category A
 * leaves uses the private {@code applySnapshot} plane ({@code AcceptedPolicyAccess.updateSampling}),
 * not platform JMX writes (P6.1 / P6.8).
 */
public record SamplingRuntimeConfig(
        boolean enabled,
        double defaultRatio,
        String[] routeRatioPrefixes,
        double[] routeRatioValues) {

    /** Источник публикации для Spring reconciliation path (RefreshScope / actuator refresh). */
    public static final String SOURCE = "spring-runtime-config";

    /**
     * Извлекает Category A schema из текущего {@link TracingProperties.Sampling} без кэширования.
     */
    public static SamplingRuntimeConfig from(TracingProperties.Sampling sampling) {
        Objects.requireNonNull(sampling, "sampling");
        SamplingRouteRatiosWire.WireArrays wire = SamplingRouteRatiosWire.fromMap(sampling.getRouteRatios());
        return new SamplingRuntimeConfig(
                sampling.isEnabled(),
                sampling.getRatio(),
                wire.prefixes(),
                wire.values());
    }
}
