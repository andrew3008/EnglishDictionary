package space.br1440.platform.tracing.autoconfigure.configuration;

import java.util.Locale;

enum PlatformEnvironment {
    LOCAL,
    SHARED,
    PRODUCTION,
    UNKNOWN;

    static PlatformEnvironment from(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return UNKNOWN;
        }
    }
}
