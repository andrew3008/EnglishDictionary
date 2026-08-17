package space.br1440.platform.tracing.otel.mdc.remote;

import org.slf4j.MDC;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;
import space.br1440.platform.tracing.runtime.remote.RemoteServiceTraceContext;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/**
 * MDC-мост для логического имени upstream-сервиса ({@link TracingMdcKeys#REMOTE_SERVICE}).
 * <p>
 * Запись и очистка выполняются только scoped-границами приложения. Agent-side processor
 * публикует значение через {@link RemoteServiceTraceContext} и не изменяет ThreadLocal MDC.
 * <p>
 * Любые ошибки MDC не должны влиять на экспорт span'ов и обработку запросов.
 */
@Slf4j
@UtilityClass
public final class RemoteServiceMdc {

    public static void putIfPresent(String remoteService) {
        if (StringUtils.isNullOrBlank(remoteService)) {
            return;
        }

        try {
            MDC.put(TracingMdcKeys.REMOTE_SERVICE, remoteService);
        } catch (RuntimeException e) {
            log.debug("Не удалось записать MDC ключ {}: {}", TracingMdcKeys.REMOTE_SERVICE, e.getMessage());
        }

    }

    public static void clear() {
        try {
            MDC.remove(TracingMdcKeys.REMOTE_SERVICE);
        } catch (RuntimeException e) {
            log.debug("Не удалось очистить MDC ключ {}: {}", TracingMdcKeys.REMOTE_SERVICE, e.getMessage());
        }
    }

}
