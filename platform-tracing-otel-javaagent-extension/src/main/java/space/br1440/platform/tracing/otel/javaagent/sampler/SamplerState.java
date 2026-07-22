package space.br1440.platform.tracing.otel.javaagent.sampler;

import space.br1440.platform.tracing.core.runtime.versioned.VersionedState;
import space.br1440.platform.tracing.core.sampling.properties.SamplingPolicyProperties;
import space.br1440.platform.tracing.core.sampling.model.RouteRatioPrefix;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicySnapshot;
import space.br1440.platform.tracing.core.sampling.properties.SamplingPolicySnapshotFactory;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Иммутабельный снимок конфигурации для {@link CompositeSampler}.
 * <p>
 * PR-6C: ratio-решения вычисляются pure-Java {@code TraceIdRatioDecision} (core.sampling.policy)
 * через {@link SamplingPolicySnapshot}, скомпилированный один раз при построении снимка.
 * <p>
 * Нормализация и компиляция вынесены в единый core-слой {@link SamplingPolicySnapshotFactory}; локальные
 * копии normalize* удалены, чтобы не было двух источников правды. Геттеры {@link #droppedRoutes()},
 * {@link #forceRecordValues()}, {@link #routeRatios()} возвращают уже нормализованные значения снимка —
 * это load-bearing контракт: {@code PlatformSamplingControl} round-trip'ит их обратно в новый снимок,
 * и нормализация идемпотентна.
 * <p>
 * Реализует {@link VersionedState}: {@code version} — простое монотонное поле, публикуется через CAS в
 * {@code VersionedStateHolder}. Построение снимка side-effect-free (может выполняться несколько раз при
 * contention в CAS-цикле); валидация {@code defaultRatio} выполняется в
 * {@link SamplingPolicySnapshotFactory} через {@link space.br1440.platform.tracing.core.sampling.properties.SamplingPolicyPropertiesValidator}
 * до создания снимка.
 */
public final class SamplerState implements VersionedState {

    private final boolean enabled;
    private final List<String> droppedRoutes;
    private final Set<String> forceRecordValues;
    private final Map<String, Double> routeRatios;
    private final double defaultRatio;
    private final long version;
    private final Instant updatedAt;
    private final String source;
    private final SamplingPolicySnapshot policySnapshot;

    public SamplerState(boolean enabled,
                        List<String> droppedRoutes,
                        Set<String> forceRecordValues,
                        Map<String, Double> routeRatios,
                        double defaultRatio,
                        long version,
                        Instant updatedAt,
                        String source) {
        // Единая компиляция/нормализация в core: фабрика валидирует defaultRatio и строит снимок.
        this.policySnapshot = SamplingPolicySnapshotFactory.create(new SamplingPolicyProperties(
                enabled, defaultRatio, droppedRoutes, forceRecordValues, routeRatios));
        this.enabled = enabled;
        // Нормализованные представления берём из снимка (единый источник правды).
        this.droppedRoutes = policySnapshot.getDroppedRoutes();
        this.forceRecordValues = policySnapshot.getForceRecordValues();
        this.routeRatios = toRatioMap(policySnapshot.getRouteRatios());
        this.defaultRatio = defaultRatio;
        this.version = version;
        this.updatedAt = updatedAt;
        this.source = source;
    }

    public boolean enabled() {
        return enabled;
    }

    public List<String> droppedRoutes() {
        return droppedRoutes;
    }

    public Set<String> forceRecordValues() {
        return forceRecordValues;
    }

    public Map<String, Double> routeRatios() {
        return routeRatios;
    }

    public double defaultRatio() {
        return defaultRatio;
    }

    @Override
    public long version() {
        return version;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public String source() {
        return source;
    }

    /** Иммутабельный снимок pure-Java policy для {@link CompositeSampler} delegation. */
    public SamplingPolicySnapshot policySnapshot() {
        return policySnapshot;
    }

    /**
     * Восстанавливает Map-представление route-ratio из нормализованного снимка (для load-bearing
     * геттера {@link #routeRatios()} и round-trip в {@code PlatformSamplingControl}). Префиксы в снимке
     * уникальны (источник — Map), поэтому восстановление без потерь.
     */
    private static Map<String, Double> toRatioMap(RouteRatioPrefix[] prefixes) {
        if (prefixes.length == 0) {
            return Map.of();
        }
        Map<String, Double> ratios = new HashMap<>(prefixes.length);
        for (RouteRatioPrefix prefix : prefixes) {
            ratios.put(prefix.prefix(), prefix.ratio());
        }
        return Collections.unmodifiableMap(ratios);
    }
}
