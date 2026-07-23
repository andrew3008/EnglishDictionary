package space.br1440.platform.tracing.otel.span.builder;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.builder.HttpClientSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.HttpServerSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.HttpTracing;
import space.br1440.platform.tracing.api.span.spec.SpanSpecAttributeValue;
import space.br1440.platform.tracing.otel.propagation.OtelTraceparentReader;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;
import space.br1440.platform.tracing.otel.span.spec.DefaultSpanSpecFactory;

import java.util.Objects;

final class DefaultHttpTracing implements HttpTracing {

    private final DefaultSpanSpecFactory specFactory;
    private final OtelTraceparentReader traceparentReader;

    DefaultHttpTracing(@Nonnull DefaultSpanSpecFactory specFactory,
                       @Nonnull OtelTraceparentReader traceparentReader) {
        this.specFactory = Objects.requireNonNull(specFactory, "specFactory");
        this.traceparentReader = Objects.requireNonNull(traceparentReader, "traceparentReader");
    }

    @Override
    @Nonnull
    public HttpServerSpanBuilder server() {
        return new HttpServerSpanBuilderImpl(specFactory, traceparentReader);
    }

    @Override
    @Nonnull
    public HttpClientSpanBuilder client() {
        return new HttpClientSpanBuilderImpl(specFactory, traceparentReader);
    }

    private static final class HttpServerSpanBuilderImpl extends AbstractSemanticSpanBuilder<HttpServerSpanBuilder>
            implements HttpServerSpanBuilder {

        HttpServerSpanBuilderImpl(@Nonnull DefaultSpanSpecFactory specFactory,
                                  @Nonnull OtelTraceparentReader traceparentReader) {
            super(specFactory, traceparentReader, SpanCategory.HTTP_SERVER,
                    SpanCategory.HTTP_SERVER.value(), "HttpServerSpanBuilder");
        }

        @Override
        protected HttpServerSpanBuilder self() {
            return this;
        }

        @Override
        @Nonnull
        public HttpServerSpanBuilder method(@Nonnull String httpMethod) {
            putAttribute(SemconvKeys.HTTP_REQUEST_METHOD.getKey(), SpanSpecAttributeValue.of(httpMethod));
            return this;
        }

        @Override
        @Nonnull
        public HttpServerSpanBuilder route(@Nonnull String route) {
            putAttribute(SemconvKeys.HTTP_ROUTE.getKey(), SpanSpecAttributeValue.of(route));
            return this;
        }

        @Override
        @Nonnull
        public HttpServerSpanBuilder statusCode(long statusCode) {
            putAttribute(SemconvKeys.HTTP_RESPONSE_STATUS_CODE.getKey(), SpanSpecAttributeValue.of(statusCode));
            return this;
        }
    }

    private static final class HttpClientSpanBuilderImpl extends AbstractSemanticSpanBuilder<HttpClientSpanBuilder>
            implements HttpClientSpanBuilder {

        HttpClientSpanBuilderImpl(@Nonnull DefaultSpanSpecFactory specFactory,
                                  @Nonnull OtelTraceparentReader traceparentReader) {
            super(specFactory, traceparentReader, SpanCategory.HTTP_CLIENT,
                    SpanCategory.HTTP_CLIENT.value(), "HttpClientSpanBuilder");
        }

        @Override
        protected HttpClientSpanBuilder self() {
            return this;
        }

        @Override
        @Nonnull
        public HttpClientSpanBuilder method(@Nonnull String httpMethod) {
            putAttribute(SemconvKeys.HTTP_REQUEST_METHOD.getKey(), SpanSpecAttributeValue.of(httpMethod));
            return this;
        }

        @Override
        @Nonnull
        public HttpClientSpanBuilder url(@Nonnull String rawUrl) {
            String sanitized = UrlSanitizer.sanitize(rawUrl);
            if (sanitized == null || sanitized.isBlank()) {
                throw new IllegalArgumentException("url must not be blank");
            }

            putAttribute(SemconvKeys.URL_FULL.getKey(), SpanSpecAttributeValue.of(sanitized));
            return this;
        }

        @Override
        @Nonnull
        public HttpClientSpanBuilder statusCode(long statusCode) {
            putAttribute(SemconvKeys.HTTP_RESPONSE_STATUS_CODE.getKey(), SpanSpecAttributeValue.of(statusCode));
            return this;
        }

        @Override
        @Nonnull
        public HttpClientSpanBuilder serverAddress(@Nonnull String address) {
            putAttribute(SemconvKeys.SERVER_ADDRESS.getKey(), SpanSpecAttributeValue.of(address));
            return this;
        }
    }
}
