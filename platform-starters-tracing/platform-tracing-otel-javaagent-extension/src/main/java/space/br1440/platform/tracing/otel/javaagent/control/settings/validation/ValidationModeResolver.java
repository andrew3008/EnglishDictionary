package space.br1440.platform.tracing.otel.javaagent.control.settings.validation;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class ValidationModeResolver {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(ValidationModeResolver.class);

    public record Resolution(ValidationMode effectiveMode) {}

    public Resolution resolve(ValidationMode requested) {
        if (requested == ValidationMode.DISABLED) {
            log.warn(AgentEventCodes.VALID_POLICY, "Validation DISABLED");
        }

        return new Resolution(requested);
    }
}
