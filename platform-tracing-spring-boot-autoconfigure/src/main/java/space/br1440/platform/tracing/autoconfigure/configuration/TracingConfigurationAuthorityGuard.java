package space.br1440.platform.tracing.autoconfigure.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Validates mode/authority/posture combination at startup.
 * <p>
 * Fail-fast matrix (throws {@link TracingConfigurationGuardException} on illegal combos):
 * <pre>
 *   AGENT  + HELM           -> OK, no Actuator write surface
 *   AGENT  + CONFIG_SERVER  -> OK, no Actuator write surface
 *   AGENT  + ACTUATOR + LOCAL       -> OK, write surface conditionally present
 *   AGENT  + ACTUATOR + SHARED      -> FAIL-FAST
 *   AGENT  + ACTUATOR + PRODUCTION  -> FAIL-FAST
 *   DISABLED + CONFIG_SERVER        -> FAIL-FAST
 *   DISABLED + ACTUATOR             -> FAIL-FAST
 *   DISABLED + HELM                 -> OK, no writer discovery
 * </pre>
 * Authority fallback is forbidden. Missing or conflicting authority must fail closed.
 */
public final class TracingConfigurationAuthorityGuard {

    private static final Logger log = LoggerFactory.getLogger(TracingConfigurationAuthorityGuard.class);

    private final TracingConfigurationAuthority authority;
    private final PlatformEnvironmentPosture posture;
    private final boolean tracingEnabled;
    private final boolean actuatorControlEnabled;

    public TracingConfigurationAuthorityGuard(
            TracingConfigurationAuthority authority,
            PlatformEnvironmentPosture posture,
            boolean tracingEnabled,
            boolean actuatorControlEnabled) {
        this.authority = authority;
        this.posture = posture;
        this.tracingEnabled = tracingEnabled;
        this.actuatorControlEnabled = actuatorControlEnabled;
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
        boolean actuatorControlEnabled = env.getProperty("platform.tracing.dev-control.enabled", Boolean.class, false);
        return new TracingConfigurationAuthorityGuard(authority, posture, tracingEnabled, actuatorControlEnabled);
    }

    /**
     * Validates the combination. Throws on illegal config. Returns {@code this} for chaining.
     */
    public TracingConfigurationAuthorityGuard validate() {
        if (!tracingEnabled) {
            if (authority == TracingConfigurationAuthority.CONFIG_SERVER) {
                throw new TracingConfigurationGuardException(
                        "DISABLED mode is incompatible with CONFIG_SERVER authority. " +
                        "Set platform.tracing.configuration.enabled=true or use HELM authority.");
            }
            if (authority == TracingConfigurationAuthority.ACTUATOR) {
                throw new TracingConfigurationGuardException(
                        "DISABLED mode is incompatible with ACTUATOR authority. " +
                        "Set platform.tracing.configuration.enabled=true.");
            }
            log.info("Tracing disabled with HELM authority: no writer discovery, no Actuator write surface.");
            return this;
        }
        if (authority == TracingConfigurationAuthority.ACTUATOR) {
            if (posture == PlatformEnvironmentPosture.SHARED) {
                throw new TracingConfigurationGuardException(
                        "ACTUATOR authority is forbidden in SHARED posture. " +
                        "platform.environment signal resolved to SHARED. " +
                        "Use CONFIG_SERVER or HELM authority.");
            }
            if (posture == PlatformEnvironmentPosture.PRODUCTION) {
                throw new TracingConfigurationGuardException(
                        "ACTUATOR authority is forbidden in PRODUCTION posture. " +
                        "platform.environment signal resolved to PRODUCTION. " +
                        "Use CONFIG_SERVER or HELM authority.");
            }
            if (!actuatorControlEnabled) {
                throw new TracingConfigurationGuardException(
                        "ACTUATOR authority requires platform.tracing.dev-control.enabled=true. " +
                        "This flag must be set explicitly to activate the mutation surface.");
            }
        }
        return this;
    }

    public boolean isActuatorMutationSurfaceAllowed() {
        return tracingEnabled
                && authority == TracingConfigurationAuthority.ACTUATOR
                && posture == PlatformEnvironmentPosture.LOCAL
                && actuatorControlEnabled;
    }

    public TracingConfigurationAuthority authority() { return authority; }
    public PlatformEnvironmentPosture posture() { return posture; }
    public boolean isTracingEnabled() { return tracingEnabled; }
    public boolean isActuatorControlEnabled() { return actuatorControlEnabled; }
}
