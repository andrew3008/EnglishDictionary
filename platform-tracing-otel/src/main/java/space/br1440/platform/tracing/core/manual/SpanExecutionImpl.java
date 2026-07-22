package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanExecution;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;
import space.br1440.platform.tracing.core.runtime.TracingRuntime;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;

import java.util.Objects;
import java.util.function.Supplier;

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
        return implementation.startSpan(SpanSpecGovernance.validateAndNormalize(spec, policy, builderName));
    }

    @Override
    public void run(@Nonnull Runnable action) {
        ScopedExecution.run(this::start, action);
    }

    @Override
    @Nonnull
    public <T> T call(@Nonnull Supplier<T> supplier) {
        return ScopedExecution.call(this::start, supplier);
    }

    @Override
    @Nonnull
    public <T> T callChecked(@Nonnull ThrowingSupplier<T> supplier) throws Exception {
        return ScopedExecution.callChecked(this::start, supplier);
    }
}
