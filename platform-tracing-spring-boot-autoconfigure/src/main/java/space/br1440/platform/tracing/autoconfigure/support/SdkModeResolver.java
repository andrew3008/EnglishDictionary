package space.br1440.platform.tracing.autoconfigure.support;

/**
 * Резолвер эффективного {@link SdkMode} (Фаза 15, PR-3).
 * <p>
 * Чистая функция от наблюдаемых признаков среды — без побочных эффектов, удобна для unit-тестов.
 * Явный режим оператора проходит проверку на совместимость с наблюдаемой средой. Для
 * {@link SdkMode#AUTO} приоритет таков: подтверждённый agent marker → external runtime → starter.
 */
public final class SdkModeResolver {

    /**
     * Наблюдаемые признаки среды.
     *
     * @param agentPresent     обнаружен OTel Java Agent ({@code OtelAgentDetector#isAgentPresent})
     * @param globalFunctional {@code GlobalOpenTelemetry} в функциональном (не no-op) состоянии
     * @param userBeanPresent  в контексте есть пользовательский {@code OpenTelemetry} bean
     */
    public record Inputs(boolean agentPresent, boolean globalFunctional, boolean userBeanPresent) {
    }

    private SdkModeResolver() {
        // utility-класс
    }

    /**
     * Возвращает эффективный режим. Явный режим оператора имеет приоритет над авто-детектом.
     */
    public static SdkMode resolve(SdkMode configured, Inputs inputs) {
        SdkMode requested = configured == null ? SdkMode.AUTO : configured;
        if (requested == SdkMode.DISABLED) {
            return SdkMode.DISABLED;
        }
        if (inputs.agentPresent() && inputs.userBeanPresent()) {
            throw new IllegalStateException(
                    "OpenTelemetry bean and active Java Agent detected simultaneously; "
                            + "remove the bean or disable the Agent");
        }

        return switch (requested) {
            case AUTO -> resolveAutomatically(inputs);
            case AGENT -> requireAgent(inputs);
            case STARTER -> requireStarterOwnership(inputs);
            case EXTERNAL -> requireExternalRuntime(inputs);
            case DISABLED -> SdkMode.DISABLED;
        };
    }

    private static SdkMode resolveAutomatically(Inputs inputs) {
        if (inputs.agentPresent()) {
            return SdkMode.AGENT;
        }
        if (inputs.globalFunctional() || inputs.userBeanPresent()) {
            return SdkMode.EXTERNAL;
        }
        return SdkMode.STARTER;
    }

    private static SdkMode requireAgent(Inputs inputs) {
        if (!inputs.agentPresent()) {
            throw new IllegalStateException(
                    "platform.tracing.sdk.mode=AGENT requires an active OpenTelemetry Java Agent marker");
        }
        return SdkMode.AGENT;
    }

    private static SdkMode requireStarterOwnership(Inputs inputs) {
        if (inputs.agentPresent()) {
            throw new IllegalStateException(
                    "platform.tracing.sdk.mode=STARTER conflicts with an active OpenTelemetry Java Agent; "
                            + "use AUTO or AGENT");
        }
        if (inputs.globalFunctional() || inputs.userBeanPresent()) {
            throw new IllegalStateException(
                    "platform.tracing.sdk.mode=STARTER conflicts with an external OpenTelemetry runtime; "
                            + "use AUTO or EXTERNAL");
        }
        return SdkMode.STARTER;
    }

    private static SdkMode requireExternalRuntime(Inputs inputs) {
        if (inputs.agentPresent()) {
            throw new IllegalStateException(
                    "platform.tracing.sdk.mode=EXTERNAL conflicts with an active OpenTelemetry Java Agent; "
                            + "use AUTO or AGENT");
        }
        if (!inputs.globalFunctional() && !inputs.userBeanPresent()) {
            throw new IllegalStateException(
                    "platform.tracing.sdk.mode=EXTERNAL requires a functional external OpenTelemetry runtime");
        }
        return SdkMode.EXTERNAL;
    }
}
