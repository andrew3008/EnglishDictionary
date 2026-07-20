package space.br1440.platform.tracing.autoconfigure.support;

import java.util.Objects;

/**
 * Проверяет единственную production-модель владения SDK: Controlled Agent либо намеренный NoOp.
 */
public final class SdkModeResolver {

    private SdkModeResolver() {
        // utility-класс
    }

    /**
     * Проверяет согласованность конфигурации и наблюдаемого runtime.
     *
     * @param configured настроенный режим
     * @param enabled значение глобального переключателя
     * @param extension наблюдаемый classloader-neutral descriptor
     * @return проверенный режим
     */
    public static SdkMode resolve(
            SdkMode configured,
            boolean enabled,
            AgentExtensionDescriptor extension) {
        SdkMode requested = Objects.requireNonNull(configured, "platform.tracing.sdk.mode must be configured");
        Objects.requireNonNull(extension, "extension");

        if (enabled != (requested == SdkMode.AGENT)) {
            throw new IllegalStateException(
                    "Contradictory platform tracing configuration: platform.tracing.enabled=" + enabled
                            + " requires platform.tracing.sdk.mode=" + (enabled ? "AGENT" : "DISABLED"));
        }
        if (requested == SdkMode.AGENT && extension.state() != AgentRuntimeState.AGENT_READY) {
            throw new IllegalStateException(runtimeFailure("AGENT", extension));
        }
        if (requested == SdkMode.DISABLED && extension.state() != AgentRuntimeState.DISABLED) {
            throw new IllegalStateException(runtimeFailure("DISABLED", extension));
        }
        return requested;
    }

    private static String runtimeFailure(String mode, AgentExtensionDescriptor extension) {
        String failureCode = extension.failureCode().isBlank() ? "NONE" : extension.failureCode();
        return "platform.tracing.sdk.mode=" + mode + " rejected observed runtime state="
                + extension.state() + ", failureCode=" + failureCode;
    }
}
