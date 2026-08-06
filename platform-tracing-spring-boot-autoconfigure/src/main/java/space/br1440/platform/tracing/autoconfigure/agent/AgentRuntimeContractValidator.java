package space.br1440.platform.tracing.autoconfigure.agent;

import lombok.experimental.UtilityClass;

import java.util.Objects;

@UtilityClass
public final class AgentRuntimeContractValidator {

    public static AgentRuntimeState validate(boolean tracingEnabled,
                                             AgentExtensionDescriptor extension) {
        Objects.requireNonNull(extension, "extension");
        if (tracingEnabled) {
            return requireReady(extension);
        }

        return requireAbsent(extension);
    }

    private static AgentRuntimeState requireReady(AgentExtensionDescriptor extension) {
        if (extension.state() != AgentRuntimeState.AGENT_READY) {
            throw new IllegalStateException(runtimeFailure(true, extension));
        }

        return extension.state();
    }

    private static AgentRuntimeState requireAbsent(AgentExtensionDescriptor extension) {
        if (extension.state() != AgentRuntimeState.DISABLED) {
            throw new IllegalStateException(runtimeFailure(false, extension));
        }

        return extension.state();
    }

    private static String runtimeFailure(boolean tracingEnabled, AgentExtensionDescriptor extension) {
        String failureCode = extension.failureCode().isBlank() ? "NONE" : extension.failureCode();
        return "platform.tracing.enabled=" + tracingEnabled + " rejected observed runtime state="
                + extension.state() + ", failureCode=" + failureCode;
    }
}
