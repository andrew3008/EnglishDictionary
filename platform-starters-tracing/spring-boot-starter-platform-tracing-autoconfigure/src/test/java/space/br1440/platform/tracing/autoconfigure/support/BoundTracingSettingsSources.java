package space.br1440.platform.tracing.autoconfigure.support;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

public final class BoundTracingSettingsSources {

    private BoundTracingSettingsSources() {
    }

    public static TracingSettingsSource sampling(boolean enabled,
                                                 Map<String, Double> routeRatios,
                                                 double defaultRatio) {
        return source(enabled, defaultRatio, routeRatios, null);
    }

    public static TracingSettingsSource validation(SpanAttributeValidationLevel validationLevel) {
        Objects.requireNonNull(validationLevel, "validationLevel");
        return source(null, null, Map.of(), validationLevel);
    }

    private static TracingSettingsSource source(Boolean enabled,
                                                Double defaultRatio,
                                                Map<String, Double> routeRatios,
                                                SpanAttributeValidationLevel validationLevel) {
        Objects.requireNonNull(routeRatios, "routeRatios");
        Map<String, String> encodedRouteRatios = new LinkedHashMap<>();
        routeRatios.forEach((route, ratio) -> encodedRouteRatios.put(route, Double.toString(ratio)));
        ExtensionConfig config = new ExtensionConfig(new StartupConfigProperties(
                enabled,
                defaultRatio,
                Map.copyOf(encodedRouteRatios),
                validationLevel
        ));
        return TracingSettingsSource.fromStartup(config, AgentInstanceId.random());
    }

    private record StartupConfigProperties(Boolean enabled,
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
