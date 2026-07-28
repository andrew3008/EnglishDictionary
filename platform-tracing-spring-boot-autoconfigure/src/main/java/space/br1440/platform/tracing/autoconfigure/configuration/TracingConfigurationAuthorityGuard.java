package space.br1440.platform.tracing.autoconfigure.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Validates authority/posture combination at startup.
 * <p>
 * Fail-fast matrix:
 * <pre>
 *   HELM           + any posture  -> OK (no writer, no Actuator write surface)
 *   CONFIG_SERVER  + any posture  -> OK (no Actuator write surface)
 *   ACTUATOR       + LOCAL        -> OK (write surface present)
 *   ACTUATOR       + SHARED       -> FAIL-FAST
 *   ACTUATOR       + PRODUCTION   -> FAIL-FAST
 *   DISABLED       + CONFIG_SERVER -> FAIL-FAST
 *   DISABLED       + ACTUATOR      -> FAIL-FAST
 * </pre>
 * Note: platform.tracing.dev-control.enabled is removed.
 * authority=ACTUATOR is the sole activation signal for mutation surface.
 */
public final class TracingConfigurationAuthorityGuard {

    private static final Logger log = LoggerFactory.getLogger(TracingConfigurationAuthorityGuard.class);

    private final TracingConfigurationAuthority authority;
    private final PlatformEnvironmentPosture posture;
    private final boolean tracingEnabled;

    public TracingConfigurationAuthorityGuard(
            TracingConfigurationAuthority authority,
            PlatformEnvironmentPosture posture,
            boolean tracingEnabled) {
        this.authority = authority;
        this.posture = posture;
        this.tracingEnabled = tracingEnabled;
    }

    public static TracingConfigurationAuthorityGuard from(Environment env) {
        String rawAuthority = env.getProperty("platform.tracing.configuration.authority", "").trim().toUpperCase();
        TracingConfigurationAuthority authority;
        try {
            authority = TracingConfigurationAuthority.valueOf(rawAuthority);
        } catch (IllegalArgumentException e) {
            throw new TracingConfigurationGuardException(
                    "Invalid platform.tracing.configuration.authority='" + rawAuthority +
                    "'. Must be one of: HELM, CONFIG_SERVER, ACTUATOR");
        }
        PlatformEnvironmentPosture posture = PlatformEnvironmentPostureResolver.resolve(env);
        boolean tracingEnabled = env.getProperty("platform.tracing.configuration.enabled", Boolean.class, true);
        return new TracingConfigurationAuthorityGuard(authority, posture, tracingEnabled);
    }

    public TracingConfigurationAuthorityGuard validate() {
        if (!tracingEnabled) {
            if (authority == TracingConfigurationAuthority.CONFIG_SERVER) {
                throw new TracingConfigurationGuardException(
                        "Tracing disabled is incompatible with CONFIG_SERVER authority.");
            }
            if (authority == TracingConfigurationAuthority.ACTUATOR) {
                throw new TracingConfigurationGuardException(
                        "Tracing disabled is incompatible with ACTUATOR authority.");
            }
            log.info("Tracing disabled with HELM authority: no writer, no Actuator write surface.");
            return this;
        }
        if (authority == TracingConfigurationAuthority.ACTUATOR) {
            if (posture == PlatformEnvironmentPosture.SHARED) {
                throw new TracingConfigurationGuardException(
                        "ACTUATOR authority is forbidden in SHARED posture. Use CONFIG_SERVER or HELM.");
            }
            if (posture == PlatformEnvironmentPosture.PRODUCTION) {
                throw new TracingConfigurationGuardException(
                        "ACTUATOR authority is forbidden in PRODUCTION posture. Use CONFIG_SERVER or HELM.");
            }
        }
        return this;
    }

    public boolean isActuatorMutationSurfaceAllowed() {
        return tracingEnabled
                && authority == TracingConfigurationAuthority.ACTUATOR
                && posture == PlatformEnvironmentPosture.LOCAL;
    }

    public TracingConfigurationAuthority authority() { return authority; }
    public PlatformEnvironmentPosture posture() { return posture; }
    public boolean isTracingEnabled() { return tracingEnabled; }
}
