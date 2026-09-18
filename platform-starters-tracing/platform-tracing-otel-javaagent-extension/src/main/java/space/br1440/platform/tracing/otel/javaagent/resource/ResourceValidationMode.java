package space.br1440.platform.tracing.otel.javaagent.resource;

import java.util.Locale;

import space.br1440.platform.common.util.StringUtils;

public enum ResourceValidationMode {

    WARNING,
    ERROR;

    public static ResourceValidationMode fromConfig(String raw) {
        if (StringUtils.isNullOrBlank(raw)) {
            return WARNING;
        }

        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "WARNING" -> WARNING;
            case "ERROR" -> ERROR;
            default -> throw new IllegalStateException("""
                    Invalid property 'platform.tracing.resource.validation-mode'='%s'. \
                    Accepted values: WARNING, ERROR\
                    """.formatted(raw)
            );
        };
    }
}
