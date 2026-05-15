package space.br1440.platform.errorhandling.mvc.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.model.ErrorEntry;

/**
 * {@link ExceptionObservabilityHook}, фиксирующий exception-event в текущем OpenTelemetry-span'е.
 *
 * <h2>Поведение</h2>
 * <ul>
 *   <li>{@link Span#recordException(Throwable)} — span получает event с типом, сообщением
 *       и stack trace оригинального исключения; визуализируется в Jaeger как красная отметка на span.</li>
 *   <li>Для 5xx (server error) — {@link Span#setStatus(StatusCode, String)} с
 *       {@link StatusCode#ERROR} и {@code errorCode} как описанием. Это критично для
 *       SLO/error-rate подсчётов: spans без явного ERROR-status считаются успешными по дефолту.</li>
 *   <li>Для 4xx (client error) span-status НЕ помечается ERROR — это рекомендация
 *       OpenTelemetry semantic conventions: 4xx это ожидаемый клиентский трафик, не ошибка сервера.</li>
 * </ul>
 */
public final class OtelExceptionRecorder implements ExceptionObservabilityHook {

    @Override
    public void onException(@NonNull Exception exception,
                            @NonNull ErrorEntry entry,
                            @NonNull String correlationId) {
        Span span = Span.current();
        span.recordException(exception);

        if (entry.getHttpStatus().is5xxServerError()) {
            span.setStatus(StatusCode.ERROR, entry.getErrorCode());
        }
    }
}
