package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.manual.HttpClientSpanBuilder;
import space.br1440.platform.tracing.api.manual.HttpServerSpanBuilder;
import space.br1440.platform.tracing.api.manual.HttpTracing;
import space.br1440.platform.tracing.api.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.sanitize.UrlSanitizer;
import space.br1440.platform.tracing.api.span.spec.SpanAttributeValue;
import space.br1440.platform.tracing.core.runtime.TracingRuntime;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;

import java.util.Objects;

final class DefaultHttpTracing implements HttpTracing {

    private final TracingRuntime implementation;
    private final AttributePolicy policy;

    DefaultHttpTracing(@Nonnull TracingRuntime implementation,
                       @Nonnull AttributePolicy policy) {
        this.implementation = Objects.requireNonNull(implementation, "implementation");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    @Nonnull
    public HttpServerSpanBuilder server() {
        return new HttpServerSpanBuilderImpl(implementation, policy);
    }

    @Override
    @Nonnull
    public HttpClientSpanBuilder client() {
        return new HttpClientSpanBuilderImpl(implementation, policy);
    }

    private static final class HttpServerSpanBuilderImpl extends AbstractSemanticSpanBuilder<HttpServerSpanBuilder>
            implements HttpServerSpanBuilder {

        HttpServerSpanBuilderImpl(@Nonnull TracingRuntime implementation,
                                  @Nonnull AttributePolicy policy) {
            super(implementation, policy, SpanCategory.HTTP_SERVER, SpanCategory.HTTP_SERVER.value(),"HttpServerSpanBuilder");
        }

        @Override
        protected HttpServerSpanBuilder self() {
            return this;
        }

        @Override
        @Nonnull
        public HttpServerSpanBuilder method(@Nonnull String httpMethod) {
            putAttribute(SemconvKeys.HTTP_REQUEST_METHOD.getKey(), SpanAttributeValue.of(httpMethod));
            return this;
        }

        @Override
        @Nonnull
        public HttpServerSpanBuilder route(@Nonnull String route) {
            putAttribute(SemconvKeys.HTTP_ROUTE.getKey(), SpanAttributeValue.of(route));
            return this;
        }

        @Override
        @Nonnull
        public HttpServerSpanBuilder statusCode(long statusCode) {
            putAttribute(SemconvKeys.HTTP_RESPONSE_STATUS_CODE.getKey(), SpanAttributeValue.of(statusCode));
            return this;
        }
    }

    private static final class HttpClientSpanBuilderImpl extends AbstractSemanticSpanBuilder<HttpClientSpanBuilder>
            implements HttpClientSpanBuilder {

        HttpClientSpanBuilderImpl(@Nonnull TracingRuntime implementation,
                                  @Nonnull AttributePolicy policy) {
            super(implementation, policy, SpanCategory.HTTP_CLIENT, SpanCategory.HTTP_CLIENT.value(),"HttpClientSpanBuilder");
        }

        @Override
        protected HttpClientSpanBuilder self() {
            return this;
        }

        @Override
        @Nonnull
        public HttpClientSpanBuilder method(@Nonnull String httpMethod) {
            putAttribute(SemconvKeys.HTTP_REQUEST_METHOD.getKey(), SpanAttributeValue.of(httpMethod));
            return this;
        }

        @Override
        @Nonnull
        public HttpClientSpanBuilder url(@Nonnull String rawUrl) {
            String sanitized = UrlSanitizer.sanitize(rawUrl);
            if (sanitized == null || sanitized.isBlank()) {
                throw new IllegalArgumentException("url must not be blank");
            }

            putAttribute(SemconvKeys.URL_FULL.getKey(), SpanAttributeValue.of(sanitized));
            return this;
        }

        @Override
        @Nonnull
        public HttpClientSpanBuilder statusCode(long statusCode) {
            putAttribute(SemconvKeys.HTTP_RESPONSE_STATUS_CODE.getKey(), SpanAttributeValue.of(statusCode));
            return this;
        }

        @Override
        @Nonnull
        public HttpClientSpanBuilder serverAddress(@Nonnull String address) {
            putAttribute(SemconvKeys.SERVER_ADDRESS.getKey(), SpanAttributeValue.of(address));
            return this;
        }
    }
}
