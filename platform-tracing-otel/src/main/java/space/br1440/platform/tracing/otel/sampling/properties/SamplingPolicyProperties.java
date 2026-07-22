package space.br1440.platform.tracing.otel.sampling.properties;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record SamplingPolicyProperties(
        boolean enabled,
        double defaultRatio,
        List<String> droppedRoutes,
        Set<String> forceRecordValues,
        Map<String, Double> routeRatios) {
}
