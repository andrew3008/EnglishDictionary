package space.br1440.platform.tracing.otel.mdc.remote;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

import java.util.Optional;

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

    private static final RemoteServiceTraceMirror TRACE_MIRROR = new RemoteServiceTraceMirror();

    public static void putIfPresent(String remoteService) {
        putIfPresent(remoteService, null);
    }

    public static void putIfPresent(String remoteService, String traceId) {
        if (remoteService == null || remoteService.isBlank()) {
            return;
        }

        try {
            MDC.put(TracingMdcKeys.REMOTE_SERVICE, remoteService);
        } catch (RuntimeException e) {
            log.debug("Не удалось записать MDC ключ {}: {}", TracingMdcKeys.REMOTE_SERVICE, e.getMessage());
        }

        TRACE_MIRROR.put(traceId, remoteService);
    }

    public static void clear() {
        try {
            MDC.remove(TracingMdcKeys.REMOTE_SERVICE);
        } catch (RuntimeException e) {
            log.debug("Не удалось очистить MDC ключ {}: {}", TracingMdcKeys.REMOTE_SERVICE, e.getMessage());
        }
    }

    public static void clearForTrace(String traceId) {
        TRACE_MIRROR.clear(traceId);
        clear();
    }

    static Optional<String> findForTrace(String traceId) {
        return TRACE_MIRROR.get(traceId);
    }
}
