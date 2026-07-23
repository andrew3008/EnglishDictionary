package space.br1440.platform.tracing.otel.span.spec;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.SpanExecution;
import space.br1440.platform.tracing.api.span.spec.SpanRelationship;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecAttributeValue;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.semconv.policy.AttributePolicy;

/**
 * Public bridge: единый governed pipeline для {@code fromSpec} и builder entry paths.
 */
public final class DefaultSpanSpecFactory {

    private final TracingRuntime runtime;
    private final AttributePolicy policy;

    public DefaultSpanSpecFactory(@Nonnull TracingRuntime runtime,
                                    @Nonnull AttributePolicy policy) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Nonnull
    public SpanExecution fromSpec(@Nonnull SpanSpec spec) {
        return new SpanExecutionImpl(
                runtime,
                Objects.requireNonNull(spec, "spec"),
                policy,
                "DefaultSpanSpecFactory.fromSpec");
    }

    @Nonnull
    public SpanExecution fromBuilderRawSpec(@Nonnull SpanSpec rawSpec,
                                            @Nonnull String builderName) {
        return new SpanExecutionImpl(
                runtime,
                Objects.requireNonNull(rawSpec, "rawSpec"),
                policy,
                Objects.requireNonNull(builderName, "builderName"));
    }

    @Nonnull
    public SpanExecution fromSemanticBuilder(@Nonnull SpanCategory category,
                                             @Nonnull String explicitName,
                                             @Nonnull SpanRelationship relationship,
                                             @Nonnull List<RemoteSpanLink> links,
                                             @Nonnull Map<String, SpanSpecAttributeValue> attributes,
                                             @Nonnull String builderName) {
        SpanSpec spec = SemanticSpanSpecs.build(
                category,
                explicitName,
                relationship,
                links,
                attributes,
                policy,
                builderName);
        return new SpanExecutionImpl(runtime, spec, policy, builderName);
    }
}
