package space.br1440.platform.errorhandling.extension;

/**
 * Трёхзначный сигнал retryable-статуса, возвращаемый {@link RootCauseExceptionHandlingStrategy}.
 *
 * <p>Используется в {@link RootCauseResolution} и обрабатывается алгоритмом агрегации в {@code RetryabilityResolver}:
 * <ul>
 *   <li>{@link #RETRYABLE} — ошибка transient, клиент может повторить запрос.</li>
 *   <li>{@link #NOT_RETRYABLE} — ошибка permanent, повтор бесполезен.
 *       Блокирует HTTP status fallback в {@code RetryabilityResolver}.</li>
 *   <li>{@link #UNSPECIFIED} — стратегия не определяет retryable-статус;
 *       решение делегируется HTTP status fallback (408/429/502/503/504).</li>
 * </ul>
 *
 * @see RootCauseResolution
 */
public enum Retryability {

    RETRYABLE,
    NOT_RETRYABLE,
    UNSPECIFIED

}
