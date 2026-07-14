package space.br1440.platform.tracing.api.propagation;

/**
 * Bridge interface for reading W3C {@code traceparent} (and optional {@code tracestate})
 * header values into {@link space.br1440.platform.tracing.api.span.RemoteSpanLink}.
 * <p>
 * The single canonical implementation is {@code OtelTraceparentReaderImpl} in
 * {@code platform-tracing-core}. This interface lives in {@code platform-tracing-api}
 * so that api-layer builders ({@code DefaultSpanSpecBuilder}) can reference it without
 * depending on OpenTelemetry or core internals.
 * <p>
 * Application code must not implement or inject this interface directly.
 * Use {@code fromTraceparent(...)} builder methods instead.
 */
public interface OtelTraceparentReader {

    /**
     * Reads a W3C {@code traceparent} header only (no {@code tracestate}).
     * {@code RemoteSpanLink.traceState} will be {@code null}.
     *
     * @param traceparent raw header value, may be {@code null} or blank
     * @return non-empty when the header is valid; empty otherwise
     */
    java.util.Optional<space.br1440.platform.tracing.api.span.RemoteSpanLink> read(
            jakarta.annotation.Nullable String traceparent);

    /**
     * Reads a W3C {@code traceparent} together with the companion {@code tracestate} header.
     * {@code RemoteSpanLink.traceState} is populated when {@code tracestate} is present and valid.
     *
     * @param traceparent raw {@code traceparent} header value
     * @param tracestate  raw {@code tracestate} header value, may be {@code null}
     * @return non-empty when {@code traceparent} is valid; empty otherwise
     */
    java.util.Optional<space.br1440.platform.tracing.api.span.RemoteSpanLink> read(
            jakarta.annotation.Nullable String traceparent,
            jakarta.annotation.Nullable String tracestate);

    /**
     * Strict variant: throws {@link IllegalArgumentException} when the header is invalid.
     *
     * @param traceparent raw header value, must not be {@code null}
     * @return a valid {@link space.br1440.platform.tracing.api.span.RemoteSpanLink}
     * @throws NullPointerException     if {@code traceparent} is {@code null}
     * @throws IllegalArgumentException if the header value is invalid
     */
    space.br1440.platform.tracing.api.span.RemoteSpanLink require(
            jakarta.annotation.Nonnull String traceparent);
}
