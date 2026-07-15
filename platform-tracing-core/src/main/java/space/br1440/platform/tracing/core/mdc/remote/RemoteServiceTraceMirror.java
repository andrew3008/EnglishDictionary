package space.br1440.platform.tracing.core.mdc.remote;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trace-scoped mirror для {@link space.br1440.platform.tracing.api.mdc.TracingMdcKeys#REMOTE_SERVICE}.
 * <p>
 * Дополняет ThreadLocal MDC при WebFlux: CLIENT-span завершается на другом потоке,
 * но traceId совпадает с SERVER-span'ом запроса — error-handling читает mirror по traceId
 * через {@link RemoteServiceNameResolver}.
 *
 * <p><b>Package-private:</b> запись только через {@link RemoteServiceMdc},
 * чтение только через {@link RemoteServiceNameResolver} — оба в одном пакете.
 */
final class RemoteServiceTraceMirror {

    private static final ConcurrentHashMap<String, String> BY_TRACE = new ConcurrentHashMap<>();

    private RemoteServiceTraceMirror() {
    }

    static void put(String traceId, String remoteService) {
        if (traceId == null || traceId.isBlank() || remoteService == null || remoteService.isBlank()) {
            return;
        }
        BY_TRACE.put(traceId, remoteService);
    }

    static Optional<String> get(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_TRACE.get(traceId))
                .filter(v -> !v.isBlank());
    }

    static void clear(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            BY_TRACE.remove(traceId);
        }
    }
}
