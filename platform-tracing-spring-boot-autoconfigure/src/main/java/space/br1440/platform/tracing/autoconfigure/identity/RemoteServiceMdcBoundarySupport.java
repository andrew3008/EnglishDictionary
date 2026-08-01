package space.br1440.platform.tracing.autoconfigure.identity;

import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.otel.mdc.remote.RemoteServiceMdc;

import lombok.experimental.UtilityClass;

/**
 * Bridge для очистки remote-service MDC и связанного trace mirror на web-boundary.
 */
@UtilityClass
public final class RemoteServiceMdcBoundarySupport {

    public static void clear(@Nullable String traceId) {
        if (traceId == null) {
            RemoteServiceMdc.clear();
            return;
        }

        RemoteServiceMdc.clearForTrace(traceId);
    }
}
