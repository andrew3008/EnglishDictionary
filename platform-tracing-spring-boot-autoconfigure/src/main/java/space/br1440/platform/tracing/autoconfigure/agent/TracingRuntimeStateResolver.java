package space.br1440.platform.tracing.autoconfigure.agent;

import java.util.Objects;

import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingMode;

/**
 * Проверяет единственную production-модель владения SDK: Controlled Agent либо намеренный NoOp.
 */
public final class TracingRuntimeStateResolver {

    private TracingRuntimeStateResolver() {
        // utility-класс
    }

    /**
     * Проверяет согласованность конфигурации и наблюдаемого runtime.
     *
     * @param configured настроенный режим
     * @param extension наблюдаемый classloader-neutral descriptor
     * @return проверенный режим
     */
    public static TracingMode resolve(
            TracingMode configured,
            AgentExtensionDescriptor extension) {
        TracingMode requested = Objects.requireNonNull(configured, "platform.tracing.mode must be configured");
        Objects.requireNonNull(extension, "extension");

        if (requested == TracingMode.AGENT && extension.state() != AgentRuntimeState.AGENT_READY) {
            throw new IllegalStateException(runtimeFailure("AGENT", extension));
        }
        if (requested == TracingMode.DISABLED && extension.state() != AgentRuntimeState.DISABLED) {
            throw new IllegalStateException(runtimeFailure("DISABLED", extension));
        }
        return requested;
    }

    private static String runtimeFailure(String mode, AgentExtensionDescriptor extension) {
        String failureCode = extension.failureCode().isBlank() ? "NONE" : extension.failureCode();
        return "platform.tracing.mode=" + mode + " rejected observed runtime state="
                + extension.state() + ", failureCode=" + failureCode;
    }
}
