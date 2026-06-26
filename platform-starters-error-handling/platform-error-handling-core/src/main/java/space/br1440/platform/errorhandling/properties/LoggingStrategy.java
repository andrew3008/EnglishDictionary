package space.br1440.platform.errorhandling.properties;

/**
 * Стратегия логирования исключений в {@code GlobalExceptionHandler}.
 *
 * <h2>Мотивация</h2>
 * <p>
 * Бинарная логика «DEBUG / ERROR для всех» неэффективна в production:
 * <ul>
 *   <li>При {@code ALL_ERROR}: логи забиваются stack trace'ами от 4xx-ошибок (404, 400),
 *       которые являются нормальным поведением клиента</li>
 *   <li>При {@code ALL_DEBUG}: пропускаются stack trace'ы от реальных 5xx-ошибок,
 *       которые требуют немедленного реагирования</li>
 * </ul>
 *
 * <h2>Стратегия {@code CATEGORY} (рекомендуемая)</h2>
 * <p>
 * Индустриальный стандарт (Netflix, Spotify, Zalando):
 * уровень логирования определяется <b>категорией HTTP-статуса ответа</b>:
 * <table>
 *   <tr><th>Категория</th><th>Уровень</th><th>Stack Trace</th><th>Обоснование</th></tr>
 *   <tr>
 *     <td>4xx Client Error</td><td>WARN</td><td>Нет</td>
 *     <td>Ошибка клиента — не проблема сервера. Stack trace засоряет логи.</td>
 *   </tr>
 *   <tr>
 *     <td>5xx Server Error</td><td>ERROR</td><td>Полный</td>
 *     <td>Серверная ошибка — нужна полная диагностика для on-call инженера.</td>
 *   </tr>
 *   <tr>
 *     <td>1xx / 2xx / 3xx</td><td>—</td><td>—</td>
 *     <td>Не логируется (ни ошибка).</td>
 *   </tr>
 * </table>
 *
 * <h2>Конфигурация</h2>
 * <pre>
 * platform:
 *   error-handling:
 *     logging-strategy: CATEGORY  # default
 * </pre>
 *
 */
public enum LoggingStrategy {

    /**
     * Логирование по категории HTTP-статуса ответа (Zalando-стиль).
     *
     * <ul>
     *   <li>4xx → {@code WARN} без stack trace (по умолчанию)</li>
     *   <li>5xx → {@code ERROR} с полным stack trace</li>
     *   <li>1xx / 2xx / 3xx → не логируется</li>
     * </ul>
     *
     * <p>Рекомендуемый режим для production. Позволяет настроить алерты
     * в мониторинге на {@code log.level=ERROR} для серверных ошибок,
     * не создавая шума от клиентских ошибок.
     *
     * <p>Stack trace для конкретных уровней логирования управляется через
     * {@link ErrorHandlingProperties#getStacktraceLogLevels() stacktrace-log-levels} —
     * набор уровней {@link space.br1440.platform.errorhandling.extension.LogPolicy}, при которых
     * stack trace включается в лог. По умолчанию пуст (stack trace только на {@code ERROR}).
     */
    CATEGORY,

    /**
     * {@code DEBUG} для всех ошибок — только имя класса и сообщение, без stack trace.
     *
     * <p>Подходит для load testing и высоко-нагруженных сред,
     * где минимизация объёма логов критична.
     */
    ALL_DEBUG,

    /**
     * {@code ERROR} с полным stack trace для <b>всех</b> ошибок (включая 4xx).
     *
     * <p>Подходит для локальной разработки и отладки.
     * <b>Не рекомендуется для production</b> — 4xx stack trace'ы засоряют логи.
     */
    ALL_ERROR

}

