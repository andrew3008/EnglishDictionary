package space.br1440.platform.tracing.core.mdc.remote;

import lombok.experimental.UtilityClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

/**
 * MDC-мост для логического имени upstream-сервиса ({@link TracingMdcKeys#REMOTE_SERVICE}).
 * <p>
 * Запись выполняется из {@code EnrichingSpanProcessor} при завершении ERROR'ного CLIENT-span'а;
 * очистка — в HTTP-фильтрах (Servlet / WebFlux) в блоке {@code finally}.
 * <p>
 * Любые ошибки MDC не должны влиять на экспорт span'ов и обработку запросов (fail-soft §37).
 *
 * <p><b>Размещение:</b> реализация находится в {@code platform-tracing-core},
 * поскольку зависит от {@code slf4j-api}. Публичный контракт SPI-типа ({@code RemoteServiceNameSource})
 * остаётся в {@code platform-tracing-api}.
 */
@UtilityClass
public final class RemoteServiceMdc {

    private static final Logger log = LoggerFactory.getLogger(RemoteServiceMdc.class);

    /**
     * Записывает имя upstream-сервиса в MDC, если значение непустое.
     */
    public static void putIfPresent(String remoteService) {
        putIfPresent(remoteService, null);
    }

    /**
     * Записывает имя upstream-сервиса в MDC и trace-scoped mirror для WebFlux error-handling.
     *
     * @param remoteService логическое имя upstream-сервиса
     * @param traceId       идентификатор текущего trace (может быть {@code null})
     */
    public static void putIfPresent(String remoteService, String traceId) {
        if (remoteService == null || remoteService.isBlank()) {
            return;
        }

        try {
            MDC.put(TracingMdcKeys.REMOTE_SERVICE, remoteService);
        } catch (RuntimeException e) {
            log.debug("Не удалось записать MDC ключ {}: {}", TracingMdcKeys.REMOTE_SERVICE, e.getMessage());
        }

        RemoteServiceTraceMirror.put(traceId, remoteService);
    }

    /**
     * Удаляет ключ upstream-сервиса из MDC. Вызывается в {@code finally} HTTP-фильтра запроса.
     */
    public static void clear() {
        try {
            MDC.remove(TracingMdcKeys.REMOTE_SERVICE);
        } catch (RuntimeException e) {
            log.debug("Не удалось очистить MDC ключ {}: {}", TracingMdcKeys.REMOTE_SERVICE, e.getMessage());
        }
    }

    /**
     * Очищает MDC и trace-scoped mirror по захваченному traceId запроса.
     *
     * @param traceId идентификатор trace, использованный при записи
     */
    public static void clearForTrace(String traceId) {
        RemoteServiceTraceMirror.clear(traceId);
        clear();
    }
}
