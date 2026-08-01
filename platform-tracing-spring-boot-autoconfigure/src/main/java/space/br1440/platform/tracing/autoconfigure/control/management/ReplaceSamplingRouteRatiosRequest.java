package space.br1440.platform.tracing.autoconfigure.control.management;

import java.util.Map;

public record ReplaceSamplingRouteRatiosRequest(Map<String, Double> routeRatios) {}
