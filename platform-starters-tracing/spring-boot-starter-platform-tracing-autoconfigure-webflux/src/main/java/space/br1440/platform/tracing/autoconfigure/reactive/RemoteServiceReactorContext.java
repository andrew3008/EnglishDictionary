package space.br1440.platform.tracing.autoconfigure.reactive;

import lombok.experimental.UtilityClass;
import reactor.util.context.Context;
import reactor.util.context.ContextView;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

import java.util.Optional;

@UtilityClass
public final class RemoteServiceReactorContext {

    public static final String CONTEXT_KEY = TracingMdcKeys.REMOTE_SERVICE;

    public static Context contextWrite(String remoteService) {
        if (remoteService == null || remoteService.isBlank()) {
            return Context.empty();
        }

        return Context.of(CONTEXT_KEY, remoteService);
    }

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
