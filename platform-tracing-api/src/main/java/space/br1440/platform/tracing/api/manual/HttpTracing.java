package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

/**
 * HTTP transport tracing entry (Slice 3A).
 */
public interface HttpTracing {

    @Nonnull
    HttpServerSpanBuilder server();

    @Nonnull
    HttpClientSpanBuilder client();
}
