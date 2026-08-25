package space.br1440.platform.tracing.otel.javaagent.control.settings.validation;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class ValidationModeResolver {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(ValidationModeResolver.class);

    public record Resolution(ValidationMode effectiveMode, boolean strictRuntimeAllowed) {}

    public Resolution resolve(PlatformEnvironment env, ValidationMode requested) {
        boolean strictAllowed = env.allowsStrictValidation();

        if (requested == ValidationMode.STRICT && !strictAllowed) {
            log.warn(AgentEventCodes.VALID_POLICY, "STRICT validation requested; downgrading to LENIENT",
                    "environment", env.name());
            return new Resolution(ValidationMode.LENIENT, false);
        }

        if (requested == ValidationMode.DISABLED) {
            if (env == PlatformEnvironment.PRODUCTION) {
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
