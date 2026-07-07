package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

/**
 * HTTP client semantic builder under {@link HttpTracing#client()}.
 */
public interface HttpClientSpanBuilder extends PlatformSpanBuilder<HttpClientSpanBuilder> {

    @Nonnull
    HttpClientSpanBuilder method(@Nonnull String httpMethod);

    @Nonnull
    HttpClientSpanBuilder url(@Nonnull String rawUrl);

    @Nonnull
    HttpClientSpanBuilder statusCode(long statusCode);

    @Nonnull
    HttpClientSpanBuilder serverAddress(@Nonnull String address);
}
