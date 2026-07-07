package space.br1440.platform.tracing.autoconfigure.reactive;

import reactor.util.context.Context;
import reactor.util.context.ContextView;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

import java.util.Optional;

/**
 * Reactor Context mirror для {@link TracingMdcKeys#REMOTE_SERVICE}.
 * <p>
 * Дополняет ThreadLocal MDC при WebFlux + async client: значение можно явно пробросить
 * через {@link #contextWrite(String)} в reactive chain или читать через
 * {@link #readFromContext(ContextView)}.
 */
public final class RemoteServiceReactorContext {

    public static final String CONTEXT_KEY = TracingMdcKeys.REMOTE_SERVICE;

    private RemoteServiceReactorContext() {
        // utility
    }

    /**
     * Возвращает функцию {@code contextWrite} для записи mirror-значения в Reactor Context.
     */
    public static Context contextWrite(String remoteService) {
        if (remoteService == null || remoteService.isBlank()) {
            return Context.empty();
        }
        return Context.of(CONTEXT_KEY, remoteService);
    }

    /**
     * Читает mirror из Reactor Context (если доступен в текущей reactive chain).
     */
    public static Optional<String> readFromContext(ContextView contextView) {
        if (contextView == null || !contextView.hasKey(CONTEXT_KEY)) {
            return Optional.empty();
        }
        Object value = contextView.get(CONTEXT_KEY);
        if (value instanceof String string && !string.isBlank()) {
            return Optional.of(string);
        }
        return Optional.empty();
    }
}
