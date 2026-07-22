package space.br1440.platform.tracing.otel.extension.configuration;

/**
 * SP-02: Control-plane environment signal, derived from the {@code platform.environment} property.
 * <p>
 * This is a deployment-time configuration value, NOT a telemetry attribute. It must never be read
 * from {@code deployment.environment.name} (the OTel resource attribute) — that is telemetry
 * metadata only and is explicitly forbidden as a control-plane guard.
 * <p>
 * Accepted values for {@code platform.environment} (case-insensitive, trimmed):
 * <ul>
 *   <li>{@code production}, {@code prod} → {@link #PRODUCTION}</li>
 *   <li>{@code staging}, {@code stage} → {@link #STAGING}</li>
 *   <li>{@code dev}, {@code development} → {@link #DEV}</li>
 *   <li>missing, blank, or any other value → {@link #UNKNOWN}</li>
 * </ul>
 * {@link #UNKNOWN} is explicitly not equivalent to dev or staging and is treated as unsafe for
 * fail-fast behavior.
 */
public enum PlatformEnvironment {

    PRODUCTION,
    STAGING,
    DEV,
    UNKNOWN;

    /**
     * Parses the raw {@code platform.environment} property value into a {@link PlatformEnvironment}.
     * Returns {@link #UNKNOWN} for any missing, blank, or unrecognized value.
     */
    public static PlatformEnvironment parse(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        return switch (value.trim().toLowerCase()) {
            case "production", "prod" -> PRODUCTION;
            case "staging", "stage" -> STAGING;
            case "dev", "development" -> DEV;
            default -> UNKNOWN;
        };
    }

    /**
     * Returns {@code true} only for environments where STRICT validation is safe:
     * {@link #DEV} and {@link #STAGING}.
     * <p>
     * {@link #PRODUCTION} and {@link #UNKNOWN} must never allow fail-fast STRICT mode.
     */
    public boolean allowsStrictValidation() {
        return this == DEV || this == STAGING;
    }
}
