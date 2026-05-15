package space.br1440.platform.errorhandling.extension;

import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.model.ErrorEntry;

/**
 * Контракт обработки исключений, не классифицированных ни одной
 * {@link TopExceptionHandlingStrategy}.
 *
 * <p>Вызывается {@code GlobalExceptionHandler} после исчерпания всех
 * специализированных стратегий. Гарантирует, что любое исключение
 * будет преобразовано в корректный {@link ErrorEntry}.
 *
 * <p>Реализация по умолчанию — {@code DefaultUnknownExceptionHandler}.
 * Пользователи стартера могут подставить свою реализацию через
 * {@code @Bean UnknownExceptionHandler}, например, для интеграции
 * с Sentry, PagerDuty или кастомной телеметрией.
 *
 * @see TopExceptionHandlingStrategy
 */
public interface UnknownExceptionHandler {

    /**
     * Обрабатывает неклассифицированное исключение и формирует {@link ErrorEntry}.
     */
    @NonNull
    ErrorEntry handle(@NonNull Exception exception, @NonNull ErrorHandlingContext context);

    /**
     * Политика логирования для неклассифицированных исключений.
     *
     * <p>По умолчанию — {@link LogPolicy#ERROR}: неклассифицированные исключения
     * всегда являются серверной ошибкой и требуют полной диагностики.
     */
    @NonNull
    default LogPolicy logPolicy(@NonNull Exception exception, @NonNull ErrorEntry entry) {
        return LogPolicy.ERROR;
    }
}
