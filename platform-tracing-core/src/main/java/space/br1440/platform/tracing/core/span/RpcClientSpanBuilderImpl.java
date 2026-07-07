package space.br1440.platform.tracing.core.span;

import io.opentelemetry.api.trace.Tracer;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.builder.RpcClientSpanBuilder;
import space.br1440.platform.tracing.core.exception.ExceptionRecorder;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;

/** Policy-backed реализация {@link RpcClientSpanBuilder} (категория RPC_CLIENT, SpanKind CLIENT). */
public final class RpcClientSpanBuilderImpl extends AbstractTypedSpanBuilder<RpcClientSpanBuilder>
        implements RpcClientSpanBuilder {

    public RpcClientSpanBuilderImpl(@Nonnull Tracer tracer, @Nonnull AttributePolicy policy,
                                    @Nonnull ExceptionRecorder exceptionRecorder) {
        super(tracer, policy, exceptionRecorder);
    }

    @Override
    protected SpanCategory category() {
        return SpanCategory.RPC_CLIENT;
    }
}
