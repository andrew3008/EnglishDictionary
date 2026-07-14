package space.br1440.platform.tracing.api.propagation;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;

import java.util.Optional;

/**
 * Bridge-интерфейс для чтения W3C {@code traceparent} (и опционального {@code tracestate})
 * из HTTP-заголовков и преобразования в {@link RemoteSpanLink}.
 */
public interface OtelTraceparentReader {

    /**
     * Читает W3C {@code traceparent} заголовок (без {@code tracestate}).
     * {@code RemoteSpanLink.traceState} будет {@code null}.
     */
    Optional<RemoteSpanLink> read(@Nullable String traceparent);

    /**
     * Читает W3C {@code traceparent} совместно со сопутствующим {@code tracestate} заголовком.
     * {@code RemoteSpanLink.traceState} заполняется, если {@code tracestate} присутствует и валиден.
     */
    Optional<RemoteSpanLink> read(@Nullable String traceparent, @Nullable String tracestate);

    /**
     * Строгий вариант метода "read": бросает {@link IllegalArgumentException}, если заголовок невалиден.
     */
    RemoteSpanLink require(@Nonnull String traceparent);

}
