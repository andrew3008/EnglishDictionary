package space.br1440.platform.tracing.otel.span.spec;

import java.util.Objects;
import java.util.function.Supplier;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.spec.SpanExecution;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.semconv.policy.AttributePolicy;

final class SpanExecutionImpl implements SpanExecution {

    private final TracingRuntime implementation;
    private final SpanSpec spec;
    private final AttributePolicy policy;
    private final String builderName;

    SpanExecutionImpl(@Nonnull TracingRuntime implementation,
                      @Nonnull SpanSpec spec,
                      @Nonnull AttributePolicy policy,
                      @Nonnull String builderName) {
        this.implementation = Objects.requireNonNull(implementation, "implementation");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.builderName = Objects.requireNonNull(builderName, "builderName");
    }

    @Override
    @Nonnull
    public SpanHandle start() {
        SpanSpec spanSpec = SpanSpecGovernance.validateAndNormalize(spec, policy, builderName);
        return implementation.startSpan(spanSpec);
    }

    @Override
    public void run(@Nonnull Runnable action) {
        SpanLifecycle.run(this::start, action);
    }

    @Override
    @Nonnull
    public <T> T call(@Nonnull Supplier<T> supplier) {
        return SpanLifecycle.call(this::start, supplier);
    }

    @Override
    @Nonnull
    public <T> T callChecked(@Nonnull ThrowingSupplier<T> supplier) throws Exception {
        return SpanLifecycle.callChecked(this::start, supplier);
    }
}
