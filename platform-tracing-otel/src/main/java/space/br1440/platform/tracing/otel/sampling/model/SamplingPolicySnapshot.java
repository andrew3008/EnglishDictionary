package space.br1440.platform.tracing.otel.sampling.model;

import lombok.Getter;

import java.util.*;

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
        this.defaultRatio = defaultRatio;
    }

    private static Set<String> normalizeForceValues(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }

        ArrayList<String> normalized = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }

            normalized.add(value.trim().toLowerCase(Locale.ROOT));
        }

        return Set.copyOf(normalized);
    }

    private static List<String> normalizeDropPaths(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        List<String> normalized = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }

            normalized.add(value.trim());
        }

        return List.copyOf(normalized);
    }

    private static RouteRatioPrefix[] normalizeRouteRatios(List<RouteRatioPrefix> values) {
        if (values == null || values.isEmpty()) {
            return new RouteRatioPrefix[0];
        }

        List<RouteRatioPrefix> sortedRouteRatios = new ArrayList<>(values);
        sortedRouteRatios.sort(ROUTE_RATIO_ORDER);
        return sortedRouteRatios.toArray(RouteRatioPrefix[]::new);
    }
}
