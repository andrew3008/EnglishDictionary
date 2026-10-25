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
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidator;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

public final class DefaultSpanSpecFactory {

    private final TracingRuntime runtime;
    private final SpanAttributeValidator policy;

    public DefaultSpanSpecFactory(@Nonnull TracingRuntime runtime,
                                  @Nonnull SpanAttributeValidator policy) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Nonnull
    public SpanExecution fromSpec(@Nonnull SpanSpec spec) {
        SpanSpec requiredSpec = Objects.requireNonNull(spec, "spec");
        if (requiredSpec.reason().isEmpty()) {
            throw new IllegalArgumentException("fromSpec requires reason(...)");
        }

        return SpanExecutionImpl.withValidationAtStart(
                runtime,
                requiredSpec,
                policy,
                "DefaultSpanSpecFactory.fromSpec"
        );
    }

    @Nonnull
    private SpanExecution fromBuilderRawSpec(@Nonnull SpanSpec rawSpec,
                                             @Nonnull String builderName) {
        return SpanExecutionImpl.withValidationAtStart(
                runtime,
                Objects.requireNonNull(rawSpec, "rawSpec"),
                policy,
                Objects.requireNonNull(builderName, "builderName")
        );
    }

    @Nonnull
    public SpanExecution fromOperation(@Nonnull SpanCategory category,
                                       @Nonnull String explicitName,
                                       @Nonnull SpanRelationship relationship,
                                       @Nonnull List<RemoteSpanLink> links,
                                       @Nonnull String builderName) {
        SpanSpec spec = OperationSpanSpecs.from(explicitName, category, relationship, links);
        return fromBuilderRawSpec(spec, builderName);
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
                builderName
        );

        return SpanExecutionImpl.prevalidated(runtime, spec);
    }
}
