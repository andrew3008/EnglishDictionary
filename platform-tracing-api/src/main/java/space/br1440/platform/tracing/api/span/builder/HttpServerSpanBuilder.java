package space.br1440.platform.tracing.api.span.builder;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.semconv.annotation.HttpSemconvVersion;

/**
 * Семантический построитель HTTP server span {@link HttpTracing#server()}.
 */
@HttpSemconvVersion("1.28.0")
public interface HttpServerSpanBuilder extends ManualSpanBuilder<HttpServerSpanBuilder> {

    @Nonnull
    HttpServerSpanBuilder method(@Nonnull String httpMethod);

    @Nonnull
    HttpServerSpanBuilder route(@Nonnull String route);

    @Nonnull
    HttpServerSpanBuilder statusCode(long statusCode);

}
