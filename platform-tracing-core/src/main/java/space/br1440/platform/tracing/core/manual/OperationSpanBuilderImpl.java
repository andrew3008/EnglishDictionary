package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.manual.OperationSpanBuilder;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.core.runtime.TracingRuntime;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;

import java.util.Objects;

final class OperationSpanBuilderImpl extends AbstractSemanticSpanBuilder<OperationSpanBuilder>
        implements OperationSpanBuilder {

    OperationSpanBuilderImpl(@Nonnull TracingRuntime implementation,
                             @Nonnull AttributePolicy policy,
                             @Nonnull String name) {
        super(implementation, policy, SpanCategory.INTERNAL, name, "OperationSpanBuilder");
    }

    @Override
    protected OperationSpanBuilder self() {
        return this;
    }
}
