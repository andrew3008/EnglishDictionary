package space.br1440.platform.tracing.otel.javaagent.resource;

import java.util.Locale;

import space.br1440.platform.tracing.otel.javaagent.utils.Strings;

public enum ResourceValidationMode {

    LENIENT,
    STRICT;

    public static ResourceValidationMode fromConfig(String raw) {
        if (Strings.isBlank(raw)) {
            return LENIENT;
        }

        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (STRICT.name().equals(normalized)) {
            return STRICT;
        }

        return LENIENT;
    }
}
