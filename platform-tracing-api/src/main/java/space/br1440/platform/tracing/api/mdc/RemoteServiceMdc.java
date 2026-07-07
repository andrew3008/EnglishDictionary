package space.br1440.platform.tracing.api.mdc;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * MDC-мост для логического имени upstream-сервиса ({@link TracingMdcKeys#REMOTE_SERVICE}).
 * <p>
 * Запись выполняется из {@code EnrichingSpanProcessor} при завершении ERROR'ного CLIENT-span'а;
 * очистка — в HTTP-фильтрах (Servlet / WebFlux) в блоке {@code finally}.
 * <p>
 * Любые ошибки MDC не должны влиять на экспорт span'ов и обработку запросов.
 */
@Slf4j
@UtilityClass
public final class RemoteServiceMdc {

    /**
     * Записывает имя upstream-сервиса в MDC, если значение непустое.
     */
    public static void putIfPresent(String remoteService) {
        putIfPresent(remoteService, null);
    }

    /**
     * Записывает имя upstream-сервиса в MDC и trace-scoped mirror для WebFlux error-handling.
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
     */
    public static void clearForTrace(String traceId) {
        RemoteServiceTraceMirror.clear(traceId);
        clear();
    }
}
