package space.br1440.platform.tracing.otel.span.spec;

import java.util.Objects;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.spec.SpanExecution;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.semconv.policy.AttributePolicy;

/**
 * PA-0 spike: предлагаемый public bridge spec-factory.
 * Единый governed pipeline; bypass-путь {@code executionFromGovernedSpec} намеренно отсутствует.
 */
public final class DefaultSpanSpecFactory {

    private final TracingRuntime runtime;
    private final AttributePolicy policy;

    public DefaultSpanSpecFactory(@Nonnull TracingRuntime runtime,
                                    @Nonnull AttributePolicy policy) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Operational ABI: внешний caller передаёт только {@link SpanSpec}; governance применяется внутри.
     */
    @Nonnull
    public SpanExecution fromSpec(@Nonnull SpanSpec spec) {
        Objects.requireNonNull(spec, "spec");
        return new ProposedSpanExecution(runtime, spec, policy, "DefaultSpanSpecFactory.fromSpec");
    }

    /**
     * Operational ABI для builder-bridge: raw spec + имя builder; governance применяется внутри.
     * Не принимает маркер «already governed».
     */
    @Nonnull
    public SpanExecution fromBuilderRawSpec(@Nonnull SpanSpec rawSpec,
                                            @Nonnull String builderName) {
        Objects.requireNonNull(rawSpec, "rawSpec");
        Objects.requireNonNull(builderName, "builderName");
        return new ProposedSpanExecution(runtime, rawSpec, policy, builderName);
    }
}
