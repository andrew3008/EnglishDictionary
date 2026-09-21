package space.br1440.platform.tracing.otel.javaagent.resource;

import java.util.Locale;

import space.br1440.platform.common.util.StringUtils;

import lombok.experimental.UtilityClass;

@UtilityClass
final class EnvironmentNormalizer {

    public static final String UNKNOWN = "unknown";

    public static String normalize(String raw, boolean enabled) {
        if (StringUtils.isNullOrBlank(raw)) {
            return UNKNOWN;
        }

        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (!enabled) {
            return value;
        }

        return switch (value) {
            case "prod", "prd", "production" -> "production";
            case "stage", "stg", "staging" -> "staging";
            case "dev", "develop", "development" -> "development";
            case "test" -> "test";
            default -> {
                if (value.startsWith("qa")) {
                    yield "test";
                }

                yield value;
            }
        };
    }
}
