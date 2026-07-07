package space.br1440.platform.tracing.core.sampling.properties;

import lombok.experimental.UtilityClass;
import space.br1440.platform.tracing.core.utils.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

@UtilityClass
public final class SamplingPolicyPropertiesValidator {

    public static final int MAX_DROP_PATHS = 100;
    public static final int MAX_FORCE_VALUES = 50;
    public static final int MAX_FORCE_VALUE_LENGTH = 255;

    public static void validate(SamplingPolicyProperties properties) {
        validateDefaultRatio(properties.defaultRatio());
        validateDropPaths(properties.droppedRoutes());
        validateForceValues(properties.forceRecordValues());
        validateRouteRatios(properties.routeRatios());
    }

    public static void validateDefaultRatio(double defaultRatio) {
        if (defaultRatio < 0.0 || defaultRatio > 1.0) {
            throw new IllegalArgumentException("defaultRatio must be in [0.0, 1.0]");
        }
    }

    private static void validateDropPaths(List<String> droppedRoutes) {
        if (droppedRoutes == null) {
            return;
        }

        if (droppedRoutes.size() > MAX_DROP_PATHS) {
            throw new IllegalArgumentException("Too many drop paths configured");
        }

        for (String path : droppedRoutes) {
            if (path == null) {
                throw new IllegalArgumentException("Drop path must not be null");
            }

            if (!path.isEmpty() && !path.startsWith("/")) {
                throw new IllegalArgumentException("Drop path must start with / or be empty");
            }
        }
    }

    private static void validateForceValues(Set<String> forceRecordValues) {
        if (forceRecordValues == null) {
            return;
        }

        if (forceRecordValues.size() > MAX_FORCE_VALUES) {
            throw new IllegalArgumentException("Too many force record values");
        }

        for (String value : forceRecordValues) {
            if (value == null) {
                throw new IllegalArgumentException("Force record value must not be null");
            }

            if (value.length() > MAX_FORCE_VALUE_LENGTH) {
                throw new IllegalArgumentException("Force record value too long");
            }
        }
    }

    private static void validateRouteRatios(Map<String, Double> routeRatios) {
        if (routeRatios == null) {
            return;
        }

        for (Map.Entry<String, Double> entry : routeRatios.entrySet()) {
            String prefix = entry.getKey();
            if (StringUtils.isNullOrBlank(prefix)) {
                throw new IllegalArgumentException("Route ratio prefix must not be null or blank");
            }

            Double ratio = entry.getValue();
            if (ratio == null || ratio < 0.0 || ratio > 1.0) {
                throw new IllegalArgumentException("Route ratio must be in [0.0, 1.0]");
            }
        }
    }
}
