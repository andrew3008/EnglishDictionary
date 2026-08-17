package space.br1440.platform.tracing.runtime.remote;

import java.util.Optional;

import lombok.experimental.UtilityClass;

/**
 * Узкий trace-scoped контекст имени upstream-сервиса для связи Agent и application plane.
 */
@UtilityClass
public final class RemoteServiceTraceContext {

    private static final RemoteServiceTraceMirror TRACE_MIRROR = new RemoteServiceTraceMirror();

    public static void record(String traceId, String remoteService) {
        TRACE_MIRROR.put(traceId, remoteService);
    }

    public static Optional<String> find(String traceId) {
        return TRACE_MIRROR.get(traceId);
    }

    public static void clear(String traceId) {
        TRACE_MIRROR.clear(traceId);
    }
}
