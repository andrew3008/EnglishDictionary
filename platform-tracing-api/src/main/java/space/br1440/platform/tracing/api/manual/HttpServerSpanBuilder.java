package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

/**
 * Семантический построитель HTTP server под {@link HttpTracing#server()}.
 */
public interface HttpServerSpanBuilder extends PlatformSpanBuilder<HttpServerSpanBuilder> {

    @Nonnull
    HttpServerSpanBuilder method(@Nonnull String httpMethod);

    @Nonnull
    HttpServerSpanBuilder route(@Nonnull String route);

    @Nonnull
    HttpServerSpanBuilder statusCode(long statusCode);
}
