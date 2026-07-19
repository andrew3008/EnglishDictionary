package space.br1440.platform.tracing.autoconfigure.support;

/**
 * Режим работы платформенного starter'а относительно OpenTelemetry SDK (Фаза 15).
 * <p>
 * Назначение — <b>диагностика и явность</b>, а не создание SDK. Платформа остаётся agent-first
 * ({@code ADR-otel-direct-integration}): starter никогда не создаёт собственный
 * {@code SdkTracerProvider}, а потребляет {@code OpenTelemetry}/{@code GlobalOpenTelemetry}.
 *
 * <ul>
 *   <li>{@link #AUTO} — режим определяется автоматически (см. {@link SdkModeResolver});</li>
 *   <li>{@link #AGENT} — подтверждён runtime marker OTel Java Agent;
 *       starter поднимает фасад поверх agent-owned global;</li>
 *   <li>{@link #EXTERNAL} — в контексте есть пользовательский {@code OpenTelemetry} bean либо
 *       без agent marker обнаружен функциональный {@code GlobalOpenTelemetry}; starter поднимает фасад поверх него;</li>
 *   <li>{@link #STARTER} — нет ни агента, ни пользовательского SDK (consume-mode без создания SDK);</li>
 *   <li>{@link #DISABLED} — платформенный фасад явно отключён ({@code NoopTraceOperations});
 *       единственный режим, где используется NoOp.</li>
 * </ul>
 */
public enum SdkMode {
    AUTO,
    AGENT,
    STARTER,
    EXTERNAL,
    DISABLED
}
