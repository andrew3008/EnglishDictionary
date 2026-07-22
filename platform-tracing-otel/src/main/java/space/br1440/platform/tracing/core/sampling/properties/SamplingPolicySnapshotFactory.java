package space.br1440.platform.tracing.core.sampling.properties;

import lombok.experimental.UtilityClass;
import space.br1440.platform.tracing.core.sampling.model.RouteRatioPrefix;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicySnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@UtilityClass
public final class SamplingPolicySnapshotFactory {

    public static SamplingPolicySnapshot create(SamplingPolicyProperties config) {
        SamplingPolicyPropertiesValidator.validateDefaultRatio(config.defaultRatio());

        List<RouteRatioPrefix> routeRatios = buildRouteRatios(config.routeRatios());
        return new SamplingPolicySnapshot(
                config.enabled(),
                config.droppedRoutes(),
                config.forceRecordValues(),
                routeRatios,
                config.defaultRatio()
        );
    }

    private static List<RouteRatioPrefix> buildRouteRatios(Map<String, Double> routeRatios) {
        if (routeRatios == null || routeRatios.isEmpty()) {
            return List.of();
        }

        List<RouteRatioPrefix> routeRatioPrefixes = new ArrayList<>(routeRatios.size());
        for (Map.Entry<String, Double> entry : routeRatios.entrySet()) {
            String route = entry.getKey();
            Double ratioValue = entry.getValue();

            if (route == null || route.isBlank() || ratioValue == null) {
                continue;
            }

            double ratio = ratioValue;
            if (ratio < 0.0 || ratio > 1.0) {
                continue;
            }

            routeRatioPrefixes.add(new RouteRatioPrefix(route.trim(), ratio));
        }

        return List.copyOf(routeRatioPrefixes);
    }
}
