package space.br1440.platform.tracing.autoconfigure.agent;

import java.util.Objects;

/**
 * Сверяет configured intent ({@code platform.tracing.enabled}) с наблюдаемым
 * {@link AgentRuntimeState}.
 *
 * <p>Проверка выполняется только в startup validation window. Dynamic attach Java Agent
 * после завершения Spring startup не является поддерживаемым сценарием и этим
 * validator не контролируется.</p>
 */
public final class AgentRuntimeContractValidator {

    private AgentRuntimeContractValidator() {
        // utility-класс
    }

    /**
     * @param tracingEnabled configured intent
     * @param extension      observed classloader-neutral descriptor
     * @return observed runtime state after successful validation
     */
    public static AgentRuntimeState validate(
            boolean tracingEnabled,
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
