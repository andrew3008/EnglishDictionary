package space.br1440.platform.tracing.autoconfigure.identity;

import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.otel.mdc.remote.RemoteServiceMdc;
import space.br1440.platform.tracing.runtime.remote.RemoteServiceTraceContext;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class RemoteServiceMdcBoundarySupport {

    public static void project(@Nullable String remoteService) {
        RemoteServiceMdc.putIfPresent(remoteService);
    }

    public static void clear(@Nullable String traceId) {
        RemoteServiceMdc.clear();
        if (traceId != null) {
            RemoteServiceTraceContext.clear(traceId);
        }
    }
}
