package space.br1440.platform.tracing.core.mdc.remote;

import org.slf4j.MDC;
import space.br1440.platform.tracing.api.mdc.RemoteServiceNameSource;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

import java.util.List;
import java.util.Optional;

/**
 * Read-chain для {@link TracingMdcKeys#REMOTE_SERVICE}: MDC → contributed sources → (опционально) built-in mirror fallback.
 * <p>
 * {@link #resolve()} — Servlet и синхронные контексты: только MDC и contributed sources.
 * {@link #resolve(String)} — WebFlux и прочие случаи, когда traceId известен явно: добавляет mirror-fallback.
 */
public final class RemoteServiceNameResolver {

    private final List<RemoteServiceNameSource> sources;

    public RemoteServiceNameResolver(List<RemoteServiceNameSource> sources) {
        this.sources = List.copyOf(sources);
    }

    public Optional<String> resolve() {
        return resolveFromMdcAndSources();
    }

    public Optional<String> resolve(String traceId) {
        return resolveFromMdcAndSources()
                .or(() -> RemoteServiceMdc.findForTrace(traceId));
    }

    private Optional<String> resolveFromMdcAndSources() {
        try {
            String fromMdc = MDC.get(TracingMdcKeys.REMOTE_SERVICE);
            if (fromMdc != null && !fromMdc.isBlank()) {
                return Optional.of(fromMdc);
            }
        } catch (RuntimeException ignored) {
            // fail-soft: ошибка чтения MDC не должна срывать error-handling
        }

        for (RemoteServiceNameSource source : sources) {
            try {
                Optional<String> value = source.resolve();
                if (value != null && value.isPresent() && !value.get().isBlank()) {
                    return value;
                }
            } catch (RuntimeException ignored) {
                // fail-soft per source
            }
        }

        return Optional.empty();
    }
}
