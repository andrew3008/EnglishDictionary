package space.br1440.platform.tracing.autoconfigure.configuration;

import org.springframework.core.env.Environment;

/**
 * Resolves {@link PlatformEnvironmentPosture} from the {@code platform.environment}
 * deployment trust signal.
 * <p>
 * Only {@code platform.environment} is consulted. Spring profile names, hostname,
 * localhost strings, IDE detection, and absence of external services are forbidden
 * as posture signals.
 */
public final class PlatformEnvironmentPostureResolver {

    private PlatformEnvironmentPostureResolver() {}

    public static PlatformEnvironmentPosture resolve(Environment environment) {
        String raw = environment.getProperty("platform.environment", "").trim().toLowerCase();
        return switch (raw) {
            case "dev", "development", "local" -> PlatformEnvironmentPosture.LOCAL;
            case "shared", "integration", "staging", "test" -> PlatformEnvironmentPosture.SHARED;
            case "production", "prod" -> PlatformEnvironmentPosture.PRODUCTION;
            default -> PlatformEnvironmentPosture.SHARED; // fail-safe: unknown = not LOCAL
        };
    }
}
