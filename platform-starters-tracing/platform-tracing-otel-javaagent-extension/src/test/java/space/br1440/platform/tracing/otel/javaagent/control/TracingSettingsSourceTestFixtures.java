package space.br1440.platform.tracing.otel.javaagent.control;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

public final class TracingSettingsSourceTestFixtures {

    private TracingSettingsSourceTestFixtures() {
        throw new AssertionError("No instances");
    }

    public static TracingSettingsSource boundDefaults(AgentInstanceId agentInstanceId) {
        return create(agentInstanceId, null, null, Map.of());
    }

    public static TracingSettingsSource boundSampling(AgentInstanceId agentInstanceId,
                                                      boolean enabled,
                                                      Map<String, Double> routeRatios,
                                                      double defaultRatio) {
        return create(agentInstanceId, enabled, defaultRatio, routeRatios);
    }

    private static TracingSettingsSource create(AgentInstanceId agentInstanceId,
                                                Boolean enabled,
                                                Double defaultRatio,
                                                Map<String, Double> routeRatios) {
        Objects.requireNonNull(agentInstanceId, "agentInstanceId");
        Objects.requireNonNull(routeRatios, "routeRatios");

        Map<String, String> encodedRouteRatios = new LinkedHashMap<>(routeRatios.size());
        routeRatios.forEach((route, ratio) -> encodedRouteRatios.put(route, Double.toString(ratio)));
        ExtensionConfig config = new ExtensionConfig(
                new SamplingConfigProperties(enabled, defaultRatio, Map.copyOf(encodedRouteRatios))
        );
        return TracingSettingsSource.fromStartup(config, agentInstanceId);
    }

    private record SamplingConfigProperties(Boolean enabled,
                                            Double defaultRatio,
                                            Map<String, String> routeRatios) implements ConfigProperties {

        @Override
        public String getString(String name) {
            return null;
        }

        @Override
        public Boolean getBoolean(String name) {
            return ExtensionPropertyNames.SAMPLING_ENABLED.equals(name) ? enabled : null;
        }

        @Override
        public Integer getInt(String name) {
            return null;
        }

        @Override
        public Long getLong(String name) {
            return null;
        }

        @Override
        public Double getDouble(String name) {
            return ExtensionPropertyNames.SAMPLING_RATIO.equals(name) ? defaultRatio : null;
        }

        @Override
        public Duration getDuration(String name) {
            return null;
        }

        @Override
        public List<String> getList(String name) {
            return null;
        }

        @Override
        public Map<String, String> getMap(String name) {
            return ExtensionPropertyNames.SAMPLING_ROUTE_RATIOS.equals(name) ? routeRatios : null;
        }
    }
}
