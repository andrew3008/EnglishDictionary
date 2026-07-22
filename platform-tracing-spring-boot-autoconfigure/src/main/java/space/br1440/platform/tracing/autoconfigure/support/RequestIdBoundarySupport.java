package space.br1440.platform.tracing.autoconfigure.support;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.otel.propagation.RequestIdSupport;

/**
 * Implementation bridge для обработки request-id на границе web-модулей.
 *
 * <p>Публичная сигнатура остаётся framework-free; канонические правила валидации и генерации
 * принадлежат implementation-модулю.</p>
 */
public final class RequestIdBoundarySupport {

    private RequestIdBoundarySupport() {
    }

    /**
     * Возвращает проверенный входящий request-id либо новый UUID.
     *
     * @param incoming сырое значение transport header
     * @return непустой нормализованный request-id
     */
    @Nonnull
    public static String resolve(@Nullable String incoming) {
        return RequestIdSupport.resolve(incoming);
    }
}
