package space.br1440.platform.tracing.core.mdc.remote;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trace-scoped mirror для {@code platform.remote.service}.
 * <p>
 * Package-private: пишет только {@link RemoteServiceMdc}, читает только {@link RemoteServiceNameResolver}.
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
                .filter(value -> !value.isBlank());
    }

    static void clear(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            BY_TRACE.remove(traceId);
        }
    }
}
