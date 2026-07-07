package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

/**
 * HTTP server semantic builder under {@link HttpTracing#server()}.
 */
public interface HttpServerSpanBuilder extends PlatformSpanBuilder<HttpServerSpanBuilder> {

    @Nonnull
    HttpServerSpanBuilder method(@Nonnull String httpMethod);

    @Nonnull
    HttpServerSpanBuilder route(@Nonnull String route);

    @Nonnull
    HttpServerSpanBuilder statusCode(long statusCode);
}
