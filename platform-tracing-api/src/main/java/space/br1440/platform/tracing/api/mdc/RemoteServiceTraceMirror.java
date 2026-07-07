package space.br1440.platform.tracing.api.mdc;

import lombok.experimental.UtilityClass;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trace-scoped mirror для {@link TracingMdcKeys#REMOTE_SERVICE}.
 * <p>
 * Дополняет ThreadLocal MDC при WebFlux: CLIENT-span завершается на другом потоке,
 * но traceId совпадает с SERVER-span'ом запроса — error-handling читает mirror по traceId.
 */
@UtilityClass
public final class RemoteServiceTraceMirror {

    private static final ConcurrentHashMap<String, String> BY_TRACE = new ConcurrentHashMap<>();

    public static void put(String traceId, String remoteService) {
        if (traceId == null || traceId.isBlank() || remoteService == null || remoteService.isBlank()) {
            return;
        }

        BY_TRACE.put(traceId, remoteService);
    }

    public static Optional<String> get(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return Optional.empty();
        }

        return Optional.ofNullable(BY_TRACE.get(traceId))
                .filter(value -> !value.isBlank());
    }

    public static void clear(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            BY_TRACE.remove(traceId);
        }
    }
}
