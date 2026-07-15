package space.br1440.platform.tracing.core.mdc.remote;

import io.opentelemetry.api.trace.Span;
import org.slf4j.MDC;
import space.br1440.platform.tracing.api.mdc.RemoteServiceNameSource;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

import java.util.List;
import java.util.Optional;

/**
 * Read-chain для {@link TracingMdcKeys#REMOTE_SERVICE}: MDC → contributed sources → built-in mirror fallback.
 */
public final class RemoteServiceNameResolver {

    private final List<RemoteServiceNameSource> sources;

    public RemoteServiceNameResolver(List<RemoteServiceNameSource> sources) {
        this.sources = List.copyOf(sources);
    }

    public Optional<String> resolve() {
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

        try {
            String traceId = Span.current().getSpanContext().getTraceId();
            return RemoteServiceTraceMirror.get(traceId);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }
}
