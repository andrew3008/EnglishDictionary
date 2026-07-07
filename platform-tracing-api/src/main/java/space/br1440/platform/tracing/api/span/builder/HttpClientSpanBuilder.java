package space.br1440.platform.tracing.api.span.builder;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.span.sanitize.UrlSanitizer;

public interface HttpClientSpanBuilder extends PlatformSpanBuilder<HttpClientSpanBuilder> {

    @Nonnull
    default HttpClientSpanBuilder method(@Nonnull String httpMethod) {
        return attribute(SemconvKeys.HTTP_REQUEST_METHOD, httpMethod);
    }

    @Nonnull
    default HttpClientSpanBuilder url(@Nonnull String rawUrl) {
        return lazyAttribute(SemconvKeys.URL_FULL, () -> UrlSanitizer.sanitize(rawUrl));
    }

    @Nonnull
    default HttpClientSpanBuilder statusCode(long statusCode) {
        return attribute(SemconvKeys.HTTP_RESPONSE_STATUS_CODE, statusCode);
    }

    @Nonnull
    default HttpClientSpanBuilder serverAddress(@Nonnull String address) {
        return attribute(SemconvKeys.SERVER_ADDRESS, address);
    }
}
