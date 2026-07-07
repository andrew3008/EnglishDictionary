package space.br1440.platform.tracing.autoconfigure.sampling;

import space.br1440.platform.tracing.autoconfigure.TracingProperties;

import java.util.List;
import java.util.Objects;

/**
 * Spring-side sampling runtime config schema v1 (PR-6E).
 * <p>
 * Тонкий view домена сэмплирования из {@link TracingProperties.Sampling}. Не является
 * вторым authoritative snapshot — agent-side {@code SamplerStateHolder} остаётся source of truth.
 * <p>
 * Runtime-mutable поля schema v1 (публикуются одним атомарным {@code updateSamplingPolicy}):
 * <ul>
 *   <li>{@code platform.tracing.sampling.enabled}</li>
 *   <li>{@code platform.tracing.sampling.ratio}</li>
 *   <li>{@code platform.tracing.sampling.drop-paths}</li>
 *   <li>{@code platform.tracing.sampling.force-record-header-values}</li>
 *   <li>{@code platform.tracing.sampling.route-ratios}</li>
 * </ul>
 * Имена заголовков ({@code forceRecordHeader}, {@code qaForceHeader}) — startup-only, не входят в JMX-домен.
 */
public record SamplingRuntimeConfig(
        boolean enabled,
        double defaultRatio,
        List<String> droppedRoutes,
        List<String> forceRecordValues,
        String[] routeRatioPrefixes,
        double[] routeRatioValues) {

    /** Источник публикации для Spring reconciliation path (RefreshScope / actuator refresh). */
    public static final String SOURCE = "spring-runtime-config";

    /**
     * Извлекает schema v1 из текущего {@link TracingProperties.Sampling} без кэширования.
     */
    public static SamplingRuntimeConfig from(TracingProperties.Sampling sampling) {
        Objects.requireNonNull(sampling, "sampling");
        SamplingRouteRatiosWire.WireArrays wire = SamplingRouteRatiosWire.fromMap(sampling.getRouteRatios());
        return new SamplingRuntimeConfig(
                sampling.isEnabled(),
                sampling.getRatio(),
                nullToEmptyList(sampling.getDropPaths()),
                nullToEmptyList(sampling.getForceRecordHeaderValues()),
                wire.prefixes(),
                wire.values());
    }

    private static List<String> nullToEmptyList(List<String> values) {
        return values == null ? List.of() : values;
    }
}
