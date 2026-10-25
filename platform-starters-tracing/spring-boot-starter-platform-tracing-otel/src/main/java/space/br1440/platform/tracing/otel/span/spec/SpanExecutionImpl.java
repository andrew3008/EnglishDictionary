package space.br1440.platform.tracing.otel.span.spec;

import java.util.Objects;
import java.util.function.Supplier;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.spec.SpanExecution;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidator;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

final class SpanExecutionImpl implements SpanExecution {

    private final TracingRuntime implementation;
    private final Supplier<SpanSpec> specSupplier;

    private SpanExecutionImpl(@Nonnull TracingRuntime implementation,
                              @Nonnull Supplier<SpanSpec> specSupplier) {
        this.implementation = Objects.requireNonNull(implementation, "implementation");
        this.specSupplier = Objects.requireNonNull(specSupplier, "specSupplier");
    }

    static SpanExecutionImpl withValidationAtStart(@Nonnull TracingRuntime implementation,
                                                   @Nonnull SpanSpec spec,
                                                   @Nonnull SpanAttributeValidator policy,
                                                   @Nonnull String builderName) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(builderName, "builderName");

        return new SpanExecutionImpl(
                implementation,
                () -> SpanSpecValidation.validateAndNormalize(spec, policy, builderName)
        );
    }

    static SpanExecutionImpl prevalidated(@Nonnull TracingRuntime implementation,
                                          @Nonnull SpanSpec spec) {
        Objects.requireNonNull(spec, "spec");
        return new SpanExecutionImpl(implementation, () -> spec);
    }

    @Override
    @Nonnull
    public SpanHandle start() {
        return implementation.startSpan(specSupplier.get());
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
