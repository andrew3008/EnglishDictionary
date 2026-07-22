package space.br1440.platform.tracing.otel.javaagent.configuration;

import lombok.experimental.UtilityClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SP-02: Resolves the effective startup validation mode from the requested mode and the
 * control-plane environment signal.
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

    private static final Logger log = LoggerFactory.getLogger(ValidationModeResolver.class);

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
     *                  (or derived from legacy {@code platform.tracing.validation.strict})
     * @return resolution containing effective mode and runtime-STRICT permission flag
     */
    public Resolution resolve(PlatformEnvironment env, ValidationMode requested) {
        boolean strictAllowed = env.allowsStrictValidation();

        if (requested == ValidationMode.STRICT && !strictAllowed) {
            // SP-02 startup downgrade: production/UNKNOWN must never run fail-fast STRICT.
            // TODO(SP-02/audit): increment validation.strict.downgrade counter via metrics hook
            log.warn("SP-02: STRICT validation requested but platform.environment={} does not allow "
                    + "fail-fast mode; downgrading to LENIENT. Runtime STRICT updates will also be "
                    + "rejected. To allow STRICT, set platform.environment=dev or staging.", env);
            return new Resolution(ValidationMode.LENIENT, false);
        }

        if (requested == ValidationMode.DISABLED) {
            if (env == PlatformEnvironment.PRODUCTION) {
                // SP-02 risk visibility: DISABLED in production is intentionally risky.
                // TODO(SP-02/audit): increment validation.disabled.production counter via metrics hook
                log.warn("SP-02: Validation DISABLED in production environment. "
                        + "Missing platform.trace.type / platform.trace.result attributes will NOT "
                        + "be detected at runtime. Ensure this is intentional.");
            } else if (env == PlatformEnvironment.UNKNOWN) {
                log.warn("SP-02: Validation DISABLED and platform.environment is UNKNOWN (missing or "
                        + "unrecognized). Risk visibility: span attribute validation is inactive.");
            }
            return new Resolution(ValidationMode.DISABLED, false);
        }

        if (requested == ValidationMode.LENIENT && env == PlatformEnvironment.UNKNOWN) {
            log.warn("SP-02: platform.environment is UNKNOWN (missing or unrecognized). "
                    + "Validation is LENIENT (warn-only). "
                    + "Set platform.environment=production|staging|dev explicitly.");
        }

        return new Resolution(requested, strictAllowed);
    }
}
