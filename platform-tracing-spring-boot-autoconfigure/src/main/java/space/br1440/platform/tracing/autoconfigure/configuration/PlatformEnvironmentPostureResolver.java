package space.br1440.platform.tracing.autoconfigure.configuration;

import org.springframework.core.env.Environment;

/**
 * Resolves {@link PlatformEnvironmentPosture} from the {@code platform.environment}
 * deployment trust signal.
 * <p>
 * Accepted values (case-insensitive): LOCAL, SHARED, PRODUCTION.
 * <strong>No aliases.</strong> dev, development, prod, test, staging are rejected.
 * Unknown or missing value throws {@link TracingConfigurationGuardException} (fail-fast).
 */
public final class PlatformEnvironmentPostureResolver {

    private PlatformEnvironmentPostureResolver() {}

    public static PlatformEnvironmentPosture resolve(Environment environment) {
        String raw = environment.getProperty("platform.environment", "").trim().toUpperCase();
        return switch (raw) {
            case "LOCAL"      -> PlatformEnvironmentPosture.LOCAL;
            case "SHARED"     -> PlatformEnvironmentPosture.SHARED;
            case "PRODUCTION" -> PlatformEnvironmentPosture.PRODUCTION;
            default -> throw new TracingConfigurationGuardException(
                    "Unknown platform.environment value: '" + raw +
                    "'. Accepted exact values: LOCAL, SHARED, PRODUCTION. " +
                    "Aliases (dev, development, prod, test, staging) are not accepted.");
        };
    }
}
