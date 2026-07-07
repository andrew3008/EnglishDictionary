package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpecifiedSpan;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;
import space.br1440.platform.tracing.core.impl.TracingImplementation;

import java.util.Objects;
import java.util.function.Supplier;

final class SpecifiedSpanImpl implements SpecifiedSpan {

    private final TracingImplementation implementation;
    private final SpanSpec spec;

    SpecifiedSpanImpl(@Nonnull TracingImplementation implementation, @Nonnull SpanSpec spec) {
        this.implementation = Objects.requireNonNull(implementation, "implementation");
        this.spec = Objects.requireNonNull(spec, "spec");
    }

    @Override
    @Nonnull
    public SpanHandle start() {
        return implementation.startSpan(spec);
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
