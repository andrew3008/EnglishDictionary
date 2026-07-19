package space.br1440.platform.tracing.core.propagation;

import java.util.Optional;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.span.RemoteSpanLink;

/**
 * Внутренний implementation-контракт чтения W3C trace context для manual tracing pipeline.
 */
public interface OtelTraceparentReader {

    Optional<RemoteSpanLink> read(@Nullable String traceparent);

    Optional<RemoteSpanLink> read(@Nullable String traceparent, @Nullable String tracestate);

    RemoteSpanLink require(@Nonnull String traceparent);
}
