package space.br1440.platform.tracing.api.propagation;

import jakarta.annotation.Nullable;

/**
 * Контракт валидации и генерации correlation id ({@code X-Request-Id}).
 * <p>
 * Единственная каноническая реализация — {@code RequestIdSupportImpl} в модуле
 * {@code platform-tracing-core}. Получить экземпляр следует через
 * {@link RequestIdSupports#get()}, а не через прямое обращение к impl-классу.
 * <p>
 * Прикладной код не должен реализовывать этот интерфейс.
 */
public interface RequestIdSupport {

    /**
     * Максимально допустимая длина correlation id.
     * Превышение трактуется как аномалия и ведёт к reject (возврат {@code null} из {@link #sanitizeOrNull}).
     */
    int MAX_LENGTH = 128;

    /**
     * Валидирует и нормализует входящий correlation id.
     * Допустимый алфавит: {@code [A-Za-z0-9_-]}, длина от 1 до {@value #MAX_LENGTH}.
     *
     * @param raw входящее значение заголовка; допускается {@code null}
     * @return trimmed id, если значение валидно; {@code null} иначе
     */
    @Nullable
    String sanitizeOrNull(@Nullable String raw);

    /**
     * Возвращает валидный correlation id: санированный входящий, либо свежий UUIDv4.
     *
     * @param incoming входящее значение заголовка; допускается {@code null}
     * @return непустая строка, пригодная как {@code X-Request-Id}
     */
    String resolve(@Nullable String incoming);
}
