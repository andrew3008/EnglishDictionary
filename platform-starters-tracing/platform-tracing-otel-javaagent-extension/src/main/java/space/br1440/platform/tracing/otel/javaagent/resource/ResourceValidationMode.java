package space.br1440.platform.tracing.otel.javaagent.resource;

import java.util.Locale;

import space.br1440.platform.common.util.StringUtils;

public enum ResourceValidationMode {

    LENIENT,
    STRICT;

    public static ResourceValidationMode fromConfig(String raw) {
        if (StringUtils.isNullOrBlank(raw)) {
            return LENIENT;
        }

        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (STRICT.name().equals(normalized)) {
            return STRICT;
        }

        return LENIENT;
    }
}
