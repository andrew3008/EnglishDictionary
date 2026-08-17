package space.br1440.platform.tracing.runtime.sampling.properties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import space.br1440.platform.common.util.MapUtils;
import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.runtime.sampling.model.RouteRatioPrefix;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicySnapshot;

import lombok.experimental.UtilityClass;

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
        if (MapUtils.isNullOrEmpty(routeRatios)) {
            return List.of();
        }

        List<RouteRatioPrefix> routeRatioPrefixes = new ArrayList<>(routeRatios.size());
        for (Map.Entry<String, Double> entry : routeRatios.entrySet()) {
            String route = entry.getKey();
            Double ratioValue = entry.getValue();

            if (StringUtils.isNullOrBlank(route) || ratioValue == null) {
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
