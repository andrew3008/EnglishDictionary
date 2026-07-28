package space.br1440.platform.tracing.autoconfigure.configuration.actuator;

import java.util.Map;

public record ReplaceSamplingRouteRatiosRequest(Map<String, Double> routeRatios) {}
