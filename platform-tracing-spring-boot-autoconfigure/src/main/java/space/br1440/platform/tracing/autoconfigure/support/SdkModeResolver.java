package space.br1440.platform.tracing.autoconfigure.support;

/**
 * Резолвер эффективного {@link SdkMode} (Фаза 15, PR-3).
 * <p>
 * Чистая функция от наблюдаемых признаков среды — без побочных эффектов, удобна для unit-тестов.
 * Если оператор задал явный режим (не {@link SdkMode#AUTO}), он возвращается как есть; иначе режим
 * детектируется по приоритету: agent → external → starter.
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
        if (configured != null && configured != SdkMode.AUTO) {
            // Явное значение оператора (включая DISABLED) — уважаем без авто-детекта.
            return configured;
        }
        // Agent-first: наличие агента/функционального global — это режим AGENT.
        if (inputs.agentPresent() || inputs.globalFunctional()) {
            return SdkMode.AGENT;
        }
        // Пользовательский SDK-bean без агента — EXTERNAL (фасад поверх него).
        if (inputs.userBeanPresent()) {
            return SdkMode.EXTERNAL;
        }
        // Нет ни агента, ни bean — consume-mode без создания SDK.
        return SdkMode.STARTER;
    }
}
