package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.propagation.OtelTraceparentReader;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.builder.OperationSpanBuilder;
import space.br1440.platform.tracing.core.runtime.TracingRuntime;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;

final class OperationSpanBuilderImpl extends AbstractSemanticSpanBuilder<OperationSpanBuilder>
        implements OperationSpanBuilder {

    OperationSpanBuilderImpl(@Nonnull TracingRuntime implementation,
                             @Nonnull AttributePolicy policy,
                             @Nonnull OtelTraceparentReader traceparentReader,
                             @Nonnull String name) {
        super(implementation, policy, traceparentReader, SpanCategory.INTERNAL, name, "OperationSpanBuilder");
    }

    @Override
    protected OperationSpanBuilder self() {
        return this;
    }
}
