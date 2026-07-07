package space.br1440.platform.tracing.api.span.builder;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.semconv.SemconvKeys;

public interface HttpServerSpanBuilder extends PlatformSpanBuilder<HttpServerSpanBuilder> {

    @Nonnull
    default HttpServerSpanBuilder method(@Nonnull String httpMethod) {
        return attribute(SemconvKeys.HTTP_REQUEST_METHOD, httpMethod);
    }

    @Nonnull
    default HttpServerSpanBuilder route(@Nonnull String route) {
        return attribute(SemconvKeys.HTTP_ROUTE, route);
    }

    @Nonnull
    default HttpServerSpanBuilder statusCode(long statusCode) {
        return attribute(SemconvKeys.HTTP_RESPONSE_STATUS_CODE, statusCode);
    }
}
