package space.br1440.platform.errorhandling.extension;

/**
 * Политика логирования для одного события обработки исключения.
 *
 * <p>Часть публичного SPI: используется в сигнатурах
 * {@link TopExceptionHandlingStrategy#logPolicy},
 * {@link UnknownExceptionHandler#logPolicy} и
 * {@link RootCauseExceptionHandlingStrategy#logPolicy}.
 *
 * <p>Отвечает только на вопрос "как логировать?" и намеренно отделена
 * от {@code ErrorCategory} (доменная классификация ошибок для бизнес-логики).
 */
public enum LogPolicy {

    /**
     * Логирование на уровне {@code ERROR} с полным stack trace.
     * <p>
     * Применяется для 5xx-ошибок сервера, требующих немедленного расследования.
     */
    ERROR,

    /**
     * Логирование на уровне {@code WARN} без stack trace.
     * <p>
     * Применяется для 4xx-ошибок клиента (400 Bad Request, 413 Payload Too Large),
     * которые требуют внимания, но не являются серверными сбоями.
     */
    WARN,

    /**
     * Логирование на уровне {@code INFO} без stack trace (по умолчанию).
     * <p>
     * Применяется для событий, которые не являются ни ошибками ({@code ERROR}),
     * ни предупреждениями ({@code WARN}), но заслуживают фиксации:
     * rate limit при нормальной нагрузке, circuit breaker в half-open,
     * штатные security-события.
     */
    INFO,

    /**
     * Логирование на уровне {@code DEBUG}.
     * <p>
     * Применяется для ожидаемых событий маршрутизации (404, 405, 406, 415),
     * которые являются нормальным трафиком, а не признаком ошибки.
     */
    DEBUG;

}
