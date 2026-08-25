package space.br1440.platform.tracing.otel.javaagent.control.settings.validation;

import space.br1440.platform.common.util.StringUtils;

public enum PlatformEnvironment {

    PRODUCTION,
    STAGING,
    DEV,
    UNKNOWN;
    public static PlatformEnvironment parse(String value) {
        if (StringUtils.isNullOrBlank(value)) {
            return UNKNOWN;
        }

        return switch (value.trim().toLowerCase()) {
            case "production", "prod" -> PRODUCTION;
            case "staging", "stage" -> STAGING;
            case "dev", "development" -> DEV;
            default -> UNKNOWN;
        };
    }

    public boolean allowsStrictValidation() {
        return (this == DEV) || (this == STAGING);
    }
}
