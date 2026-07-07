package space.br1440.platform.tracing.core.span;

import io.opentelemetry.api.trace.Tracer;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.builder.HttpClientSpanBuilder;
import space.br1440.platform.tracing.core.exception.ExceptionRecorder;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;

/** Policy-backed реализация {@link HttpClientSpanBuilder} (категория HTTP_CLIENT, SpanKind CLIENT). */
public final class HttpClientSpanBuilderImpl extends AbstractTypedSpanBuilder<HttpClientSpanBuilder>
        implements HttpClientSpanBuilder {

    public HttpClientSpanBuilderImpl(@Nonnull Tracer tracer, @Nonnull AttributePolicy policy,
                                     @Nonnull ExceptionRecorder exceptionRecorder) {
        super(tracer, policy, exceptionRecorder);
    }

    @Override
    protected SpanCategory category() {
        return SpanCategory.HTTP_CLIENT;
    }
}
