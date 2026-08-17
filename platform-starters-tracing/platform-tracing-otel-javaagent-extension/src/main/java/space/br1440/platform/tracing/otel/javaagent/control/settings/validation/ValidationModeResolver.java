package space.br1440.platform.tracing.otel.javaagent.control.settings.validation;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;

import lombok.experimental.UtilityClass;

/**
 * SP-02: Resolves the effective startup validation mode from the requested mode and the
 * control-plane environment signal. The same {@link Resolution#strictRuntimeAllowed()} value
 * that is produced here is what {@code TracingSettingsSource} threads into {@code
 * SettingsValidationConstraints} for runtime JMX mutation — startup and runtime share this single
 * resolver output rather than recomputing the guard independently.
 * <p>
 * <b>Guard rules:</b>
 * <ul>
 *   <li>{@code dev}/{@code staging} + {@code STRICT} → allow; runtime STRICT updates also
 *       allowed</li>
 *   <li>{@code production}/{@code UNKNOWN} + {@code STRICT} → downgrade to {@code LENIENT} +
 *       WARN; runtime STRICT updates also rejected</li>
 *   <li>{@code DISABLED} in any environment → allow + WARN (risk visibility)</li>
 *   <li>{@code LENIENT} in {@code UNKNOWN} → allow + WARN</li>
 * </ul>
 *
 * <b>Invariant:</b> {@code deployment.environment.name} (the OTel resource attribute) is NOT
 * read here and must never be used as a control-plane guard. Only {@link PlatformEnvironment}
 * derived from {@code platform.environment} is accepted.
 */
@UtilityClass
public final class ValidationModeResolver {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(ValidationModeResolver.class);

    /**
     * The resolved startup configuration: effective mode + whether runtime updates to STRICT
     * are permitted.
     *
     * @param effectiveMode       the mode that will actually be active at startup (may be
     *                            downgraded from requested)
     * @param strictRuntimeAllowed whether runtime policy updates may enable {@code strict=true}
     */
    public record Resolution(ValidationMode effectiveMode, boolean strictRuntimeAllowed) {}

    /**
     * Resolves the effective validation mode for startup, applying the environment-safety guard.
     *
     * @param env       control-plane environment parsed from {@code platform.environment}
     * @param requested requested validation mode parsed from {@code platform.tracing.validation.mode}
     * @return resolution containing effective mode and runtime-STRICT permission flag
     */
    public Resolution resolve(PlatformEnvironment env, ValidationMode requested) {
        boolean strictAllowed = env.allowsStrictValidation();

        if (requested == ValidationMode.STRICT && !strictAllowed) {
            // SP-02 startup downgrade: production/UNKNOWN must never run fail-fast STRICT.
            log.warn(AgentEventCodes.VALID_POLICY, "STRICT validation requested; downgrading to LENIENT",
                    "environment", env.name());
            return new Resolution(ValidationMode.LENIENT, false);
        }

        if (requested == ValidationMode.DISABLED) {
            if (env == PlatformEnvironment.PRODUCTION) {
                // SP-02 risk visibility: DISABLED in production is intentionally risky.
                log.warn(AgentEventCodes.VALID_POLICY, "Validation DISABLED in production",
                        "environment", env.name());
            } else if (env == PlatformEnvironment.UNKNOWN) {
                log.warn(AgentEventCodes.VALID_POLICY, "Validation DISABLED and platform.environment is UNKNOWN",
                        "environment", env.name());
            }
            return new Resolution(ValidationMode.DISABLED, false);
        }

        if (requested == ValidationMode.LENIENT && env == PlatformEnvironment.UNKNOWN) {
            log.warn(AgentEventCodes.VALID_POLICY, "platform.environment is UNKNOWN; validation is LENIENT",
                    "environment", env.name());
        }

        return new Resolution(requested, strictAllowed);
    }
}
