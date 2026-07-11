package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

/**
 * Точка входа в трассировку HTTP-транспорта (Slice 3A).
 */
public interface HttpTracing {

    @Nonnull
    HttpServerSpanBuilder server();

    @Nonnull
    HttpClientSpanBuilder client();
}
