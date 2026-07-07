package space.br1440.platform.tracing.core.span;

import io.opentelemetry.api.trace.Tracer;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.builder.RpcServerSpanBuilder;
import space.br1440.platform.tracing.core.exception.ExceptionRecorder;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;

/** Policy-backed реализация {@link RpcServerSpanBuilder} (категория RPC_SERVER, SpanKind SERVER). */
public final class RpcServerSpanBuilderImpl extends AbstractTypedSpanBuilder<RpcServerSpanBuilder>
        implements RpcServerSpanBuilder {

    public RpcServerSpanBuilderImpl(@Nonnull Tracer tracer, @Nonnull AttributePolicy policy,
                                    @Nonnull ExceptionRecorder exceptionRecorder) {
        super(tracer, policy, exceptionRecorder);
    }

    @Override
    protected SpanCategory category() {
        return SpanCategory.RPC_SERVER;
    }
}
