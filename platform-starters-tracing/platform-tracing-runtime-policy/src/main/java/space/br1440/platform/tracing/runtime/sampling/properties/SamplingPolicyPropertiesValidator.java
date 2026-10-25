package space.br1440.platform.tracing.runtime.sampling.properties;

import java.util.List;
import java.util.Objects;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class SamplingPolicyPropertiesValidator {

    public static final int MAX_DROP_PATHS = 100;
    public static final int MAX_FORCE_VALUES = 50;
    public static final int MAX_FORCE_VALUE_LENGTH = 255;

    public static void validate(List<String> droppedRoutes, List<String> forceRecordValues) {
        validateDropPaths(Objects.requireNonNull(droppedRoutes, "droppedRoutes"));
        validateForceValues(Objects.requireNonNull(forceRecordValues, "forceRecordValues"));
    }

    private static void validateDropPaths(List<String> droppedRoutes) {
        if (droppedRoutes.size() > MAX_DROP_PATHS) {
            throw new IllegalArgumentException("Too many drop paths configured");
        }

        for (String path : droppedRoutes) {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("Drop path must not be null or blank");
            }

            if (!path.trim().startsWith("/")) {
                throw new IllegalArgumentException("Drop path must start with /");
            }
        }
    }

    private static void validateForceValues(List<String> forceRecordValues) {
        if (forceRecordValues.size() > MAX_FORCE_VALUES) {
            throw new IllegalArgumentException("Too many force record values");
        }

        for (String value : forceRecordValues) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Force record value must not be null or blank");
            }

            if (value.trim().length() > MAX_FORCE_VALUE_LENGTH) {
                throw new IllegalArgumentException("Force record value too long");
            }
        }
    }
}
