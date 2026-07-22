package space.br1440.platform.tracing.otel.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.otel.propagation.OtelTraceparentReader;
import space.br1440.platform.tracing.api.span.builder.DatabaseSpanBuilder;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.SpanSpecAttributeValue;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.semconv.policy.AttributePolicy;

final class DatabaseSpanBuilderImpl extends AbstractSemanticSpanBuilder<DatabaseSpanBuilder> implements DatabaseSpanBuilder {

    DatabaseSpanBuilderImpl(@Nonnull TracingRuntime implementation,
                            @Nonnull AttributePolicy policy,
                            @Nonnull OtelTraceparentReader traceparentReader) {
        super(implementation, policy, traceparentReader, SpanCategory.DATABASE,
                SpanCategory.DATABASE.value(), "DatabaseSpanBuilder");
    }

    @Override
    protected DatabaseSpanBuilder self() {
        return this;
    }

    @Override
    @Nonnull
    public DatabaseSpanBuilder system(@Nonnull String dbSystem) {
        putAttribute(SemconvKeys.DB_SYSTEM_NAME.getKey(), SpanSpecAttributeValue.of(dbSystem));
        return this;
    }

    @Override
    @Nonnull
    public DatabaseSpanBuilder operation(@Nonnull String operation) {
        putAttribute(SemconvKeys.DB_OPERATION_NAME.getKey(), SpanSpecAttributeValue.of(operation));
        return this;
    }

    @Override
    @Nonnull
    public DatabaseSpanBuilder collection(@Nonnull String collection) {
        putAttribute(SemconvKeys.DB_COLLECTION_NAME.getKey(), SpanSpecAttributeValue.of(collection));
        return this;
    }

    @Nonnull
    @Override
    protected SpanSpec toSpanSpec() {
        requireAttribute(SemconvKeys.DB_OPERATION_NAME.getKey(), "operation");
        requireSystemAttribute();
        requireAttribute(SemconvKeys.DB_COLLECTION_NAME.getKey(), "collection");
        return super.toSpanSpec();
    }

    private void requireAttribute(@Nonnull String key, @Nonnull String label) {
        if (!attributes.containsKey(key)) {
            throw new IllegalArgumentException(label + " is required");
        }
    }

    private void requireSystemAttribute() {
        if (!attributes.containsKey(SemconvKeys.DB_SYSTEM_NAME.getKey())
                && !attributes.containsKey(SemconvKeys.DB_SYSTEM_LEGACY.getKey())) {
            throw new IllegalArgumentException("system is required");
        }
    }
}
