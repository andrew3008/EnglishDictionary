package space.br1440.platform.tracing.api.span.builder;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.semconv.annotation.HttpSemconvVersion;

/**
 * Семантический builder HTTP client span {@link HttpTracing#client()}.
 */
@HttpSemconvVersion("1.28.0")
public interface HttpClientSpanBuilder extends ManualSpanBuilder<HttpClientSpanBuilder> {

    @Nonnull
    HttpClientSpanBuilder method(@Nonnull String httpMethod);

    @Nonnull
    HttpClientSpanBuilder url(@Nonnull String rawUrl);

    @Nonnull
    HttpClientSpanBuilder statusCode(long statusCode);

    @Nonnull
    HttpClientSpanBuilder serverAddress(@Nonnull String address);

}
