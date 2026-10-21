package space.br1440.platform.tracing.otel.javaagent.control;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import lombok.experimental.UtilityClass;

@UtilityClass
public final class TracingSettingsSourceTestFixtures {

    public static TracingSettingsSource boundDefaults(AgentInstanceId agentInstanceId) {
        return create(agentInstanceId, null, null, Map.of(), null);
    }

    public static TracingSettingsSource boundSampling(AgentInstanceId agentInstanceId,
                                                      boolean enabled,
                                                      Map<String, Double> routeRatios,
                                                      double defaultRatio) {
        return create(agentInstanceId, enabled, defaultRatio, routeRatios, null);
    }

    public static TracingSettingsSource boundValidation(AgentInstanceId agentInstanceId,
                                                        SpanAttributeValidationLevel validationLevel) {
        Objects.requireNonNull(validationLevel, "validationLevel");
        return create(agentInstanceId, null, null, Map.of(), validationLevel);
    }

    private static TracingSettingsSource create(AgentInstanceId agentInstanceId,
                                                Boolean enabled,
                                                Double defaultRatio,
                                                Map<String, Double> routeRatios,
                                                SpanAttributeValidationLevel validationLevel) {
        Objects.requireNonNull(agentInstanceId, "agentInstanceId");
        Objects.requireNonNull(routeRatios, "routeRatios");

        Map<String, String> encodedRouteRatios = new LinkedHashMap<>(routeRatios.size());
        routeRatios.forEach((route, ratio) -> encodedRouteRatios.put(route, Double.toString(ratio)));

        ExtensionConfig config = configWithSystemPropertyEvidence(
                enabled,
                defaultRatio,
                Map.copyOf(encodedRouteRatios),
                validationLevel
        );
        return TracingSettingsSource.fromStartup(config, agentInstanceId);
    }

    private static ExtensionConfig configWithSystemPropertyEvidence(Boolean enabled,
                                                                    Double defaultRatio,
                                                                    Map<String, String> routeRatios,
                                                                    SpanAttributeValidationLevel validationLevel) {
        Map<String, String> properties = new LinkedHashMap<>();

        if (enabled != null) {
            properties.put(ExtensionPropertyNames.SAMPLING_ENABLED, enabled.toString());
        }

        if (defaultRatio != null) {
            properties.put(ExtensionPropertyNames.SAMPLING_RATIO, defaultRatio.toString());
        }

        if (validationLevel != null) {
            properties.put(ExtensionPropertyNames.SPAN_ATTRIBUTES_VALIDATION_LEVEL, validationLevel.name());
        }

        if (!routeRatios.isEmpty()) {
            properties.put(
                    ExtensionPropertyNames.SAMPLING_ROUTE_RATIOS,
                    routeRatios.entrySet().stream()
                            .map(entry -> entry.getKey() + "=" + entry.getValue())
                            .reduce((left, right) -> left + "," + right)
                            .orElseThrow());
        }

        synchronized (TracingSettingsSourceTestFixtures.class) {
            Map<String, String> previousValues = new LinkedHashMap<>();
            try {
                properties.forEach((name, value) -> {
                    previousValues.put(name, System.getProperty(name));
                    System.setProperty(name, value);
                });

                SamplingConfigProperties configProperties = new SamplingConfigProperties(
                        enabled,
                        defaultRatio,
                        routeRatios,
                        validationLevel
                );
                return new ExtensionConfig(configProperties);
            } finally {
                previousValues.forEach((name, value) -> {
                    if (value == null) {
                        System.clearProperty(name);
                    } else {
                        System.setProperty(name, value);
                    }
                });
            }
        }
    }

    private record SamplingConfigProperties(Boolean enabled,
                                            Double defaultRatio,
                                            Map<String, String> routeRatios,
                                            SpanAttributeValidationLevel validationLevel) implements ConfigProperties {

        @Override
        public String getString(String name) {
            if (validationLevel != null
                    && ExtensionPropertyNames.SPAN_ATTRIBUTES_VALIDATION_LEVEL.equals(name)) {
                return validationLevel.name();
            }
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
