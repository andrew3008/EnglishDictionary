package space.br1440.platform.tracing.core.span;

import io.opentelemetry.api.trace.Tracer;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.builder.HttpServerSpanBuilder;
import space.br1440.platform.tracing.core.exception.ExceptionRecorder;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;

/**
 * Policy-backed реализация {@link HttpServerSpanBuilder} (категория HTTP_SERVER, SpanKind SERVER).
 * Category-setter'ы наследуются из interface-default'ов; здесь только категория и общий путь старта.
 */
public final class HttpServerSpanBuilderImpl extends AbstractTypedSpanBuilder<HttpServerSpanBuilder>
        implements HttpServerSpanBuilder {

    public HttpServerSpanBuilderImpl(@Nonnull Tracer tracer, @Nonnull AttributePolicy policy,
                                     @Nonnull ExceptionRecorder exceptionRecorder) {
        super(tracer, policy, exceptionRecorder);
    }

    @Override
    protected SpanCategory category() {
        return SpanCategory.HTTP_SERVER;
    }
}
