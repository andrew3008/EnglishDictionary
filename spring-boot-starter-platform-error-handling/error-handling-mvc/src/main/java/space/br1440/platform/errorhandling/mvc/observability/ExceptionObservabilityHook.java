package space.br1440.platform.errorhandling.mvc.observability;

import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.model.ErrorEntry;

/**
 * Hook для пост-обработки разрешённого {@link ErrorEntry} наблюдательскими системами
 * (Micrometer, OpenTelemetry, кастомные SIEM-репортеры).
 *
 * <p>Все hook'и выполняются последовательно после построения {@code ErrorEntry}, ДО
 * формирования HTTP-ответа. Любой бросок внутри {@link #onException} ловится
 * {@code GlobalExceptionHandler} и логируется как WARN — он НЕ прерывает основной
 * pipeline (fail-open принцип: проблемы в observability не должны валить response).
 *
 * <h2>Контракт</h2>
 * <ul>
 *   <li>{@code exception} — оригинальное исключение, которое перехватил handler.</li>
 *   <li>{@code entry} — разрешённый и обогащённый {@code ErrorEntry} с финальным
 *       {@code httpStatus}, {@code errorCode}, {@code reason}.</li>
 *   <li>{@code correlationId} — тот же, что улетит в {@code X-Correlation-Id}.</li>
 *   <li>Реализация ОБЯЗАНА быть быстрой (Micrometer counter, OTel span event) — синхронная hot-path, любая I/O запрещена.</li>
 *   <li>Реализация ОБЯЗАНА быть thread-safe.</li>
 * </ul>
 */
public interface ExceptionObservabilityHook {

    void onException(@NonNull Exception exception,
                     @NonNull ErrorEntry entry,
                     @NonNull String correlationId);
}
