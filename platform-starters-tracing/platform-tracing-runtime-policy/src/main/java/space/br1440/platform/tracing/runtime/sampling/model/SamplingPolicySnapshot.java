package space.br1440.platform.tracing.runtime.sampling.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import space.br1440.platform.common.util.ListUtils;
import space.br1440.platform.common.util.SetUtils;
import space.br1440.platform.common.util.StringUtils;

import lombok.Getter;

@Getter
public final class SamplingPolicySnapshot {

    private static final Comparator<RouteRatioPrefix> ROUTE_RATIO_ORDER =
            Comparator.comparingInt((RouteRatioPrefix r) -> r.prefix().length())
                    .reversed()
                    .thenComparing(RouteRatioPrefix::prefix);

    private final boolean enabled;
    private final List<String> droppedRoutes;
    private final Set<String> forceRecordValues;
    private final RouteRatioPrefix[] routeRatios;
    private final Map<String, Double> routeRatioMap;
    private final double defaultRatio;

    public SamplingPolicySnapshot(boolean enabled,
                                  List<String> droppedRoutes,
                                  Set<String> forceRecordValues,
                                  List<RouteRatioPrefix> routeRatios,
                                  double defaultRatio) {
        this.enabled = enabled;
        this.droppedRoutes = normalizeDropPaths(droppedRoutes);
        this.forceRecordValues = normalizeForceValues(forceRecordValues);
        this.routeRatios = normalizeRouteRatios(routeRatios);
        this.routeRatioMap = toRouteRatioMap(this.routeRatios);
        this.defaultRatio = defaultRatio;
    }

    private static Set<String> normalizeForceValues(Set<String> values) {
        if (SetUtils.isNullOrEmpty(values)) {
            return Set.of();
        }

        ArrayList<String> normalized = new ArrayList<>(values.size());
        for (String value : values) {
            if (StringUtils.isNullOrBlank(value)) {
                continue;
            }

            normalized.add(value.trim().toLowerCase(Locale.ROOT));
        }

        return Set.copyOf(normalized);
    }

    private static List<String> normalizeDropPaths(List<String> values) {
        if (ListUtils.isNullOrEmpty(values)) {
            return List.of();
        }

        List<String> normalized = new ArrayList<>(values.size());
        for (String value : values) {
            if (StringUtils.isNullOrBlank(value)) {
                continue;
            }

            normalized.add(value.trim());
        }

        return List.copyOf(normalized);
    }

    private static RouteRatioPrefix[] normalizeRouteRatios(List<RouteRatioPrefix> values) {
        if (ListUtils.isNullOrEmpty(values)) {
            return new RouteRatioPrefix[0];
        }

        List<RouteRatioPrefix> sortedRouteRatios = new ArrayList<>(values);
        sortedRouteRatios.sort(ROUTE_RATIO_ORDER);
        return sortedRouteRatios.toArray(RouteRatioPrefix[]::new);
    }

    private static Map<String, Double> toRouteRatioMap(RouteRatioPrefix[] values) {
        if (values.length == 0) {
            return Map.of();
        }

        Map<String, Double> result = new LinkedHashMap<>(values.length);
        for (RouteRatioPrefix value : values) {
            result.put(value.prefix(), value.ratio());
        }
        return Map.copyOf(result);
    }
}
