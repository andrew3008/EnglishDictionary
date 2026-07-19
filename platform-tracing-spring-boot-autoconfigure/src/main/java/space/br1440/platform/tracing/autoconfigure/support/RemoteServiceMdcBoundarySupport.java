package space.br1440.platform.tracing.autoconfigure.support;

import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.core.mdc.remote.RemoteServiceMdc;

/**
 * Implementation bridge для очистки remote-service MDC и связанного trace mirror на web-boundary.
 */
public final class RemoteServiceMdcBoundarySupport {

    private RemoteServiceMdcBoundarySupport() {
    }

    /**
     * Очищает MDC и, если trace-id известен, соответствующую запись bounded mirror.
     *
     * @param traceId trace-id завершённого request lifecycle; может отсутствовать
     */
    public static void clear(@Nullable String traceId) {
        if (traceId == null) {
            RemoteServiceMdc.clear();
            return;
        }
        RemoteServiceMdc.clearForTrace(traceId);
    }
}
