package space.br1440.platform.tracing.api.propagation;

import jakarta.annotation.Nullable;

/**
 * Контракт валидации и генерации correlation id ({@code X-Request-Id}).
 * <p>
 * Единственная каноническая реализация — {@code RequestIdSupportImpl} в модуле
 * {@code platform-tracing-core}. Разрешение через {@link RequestIdSupports} и
 * {@link java.util.ServiceLoader} SPI.
 */
public interface RequestIdSupport {

    /** Максимально допустимая длина correlation id. Превышение трактуется как аномалия → reject. */
    int MAX_LENGTH = 128;

    /**
     * Валидирует входящий correlation id или генерирует новый UUIDv4 при отсутствии/невалидности.
     *
     * @param incoming сырое значение заголовка; допускается {@code null}
     * @return валидный correlation id (входящий или сгенерированный)
     */
    String resolve(@Nullable String incoming);

    /**
     * Санитизирует входящий correlation id: trim, allowlist {@code [A-Za-z0-9_-]}, лимит {@link #MAX_LENGTH}.
     * <p>
     * Реализация — zero-allocation на hot path (ручной char-цикл вместо {@code Pattern.matcher()}).
     *
     * @param raw сырое значение заголовка; допускается {@code null}
     * @return санитизированное значение или {@code null}, если вход невалиден
     */
    @Nullable
    String sanitizeOrNull(@Nullable String raw);

}
