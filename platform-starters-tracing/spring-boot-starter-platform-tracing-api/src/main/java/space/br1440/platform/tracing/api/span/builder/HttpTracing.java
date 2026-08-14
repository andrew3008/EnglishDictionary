package space.br1440.platform.tracing.api.span.builder;

import jakarta.annotation.Nonnull;

/**
 * Точка входа в трассировку HTTP-транспорта.
 */
public interface HttpTracing {

    @Nonnull
    HttpServerSpanBuilder server();

    @Nonnull
    HttpClientSpanBuilder client();

}
