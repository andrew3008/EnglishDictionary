package space.br1440.platform.tracing.core.span;

import io.opentelemetry.api.trace.Tracer;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.builder.DatabaseSpanBuilder;
import space.br1440.platform.tracing.core.exception.ExceptionRecorder;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;

/** Policy-backed реализация {@link DatabaseSpanBuilder} (категория DATABASE, SpanKind CLIENT). */
public final class DatabaseSpanBuilderImpl extends AbstractTypedSpanBuilder<DatabaseSpanBuilder>
        implements DatabaseSpanBuilder {

    public DatabaseSpanBuilderImpl(@Nonnull Tracer tracer, @Nonnull AttributePolicy policy,
                                   @Nonnull ExceptionRecorder exceptionRecorder) {
        super(tracer, policy, exceptionRecorder);
    }

    @Override
    protected SpanCategory category() {
        return SpanCategory.DATABASE;
    }
}
