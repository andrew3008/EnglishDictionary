package space.br1440.platform.tracing.otel.span.spec;

import java.util.Objects;
import java.util.function.Supplier;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanExecution;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.semconv.policy.AttributePolicy;

/**
 * PA-0 spike: минимальная реализация {@link SpanExecution} для compile-proof fixture.
 * Governance в PA-1 заменит заглушку на полный {@code SpanSpecGovernance}-pipeline.
 */
final class ProposedSpanExecution implements SpanExecution {

    private final TracingRuntime runtime;
    private final SpanSpec spec;
    private final AttributePolicy policy;
    private final String builderName;

    ProposedSpanExecution(@Nonnull TracingRuntime runtime,
                          @Nonnull SpanSpec spec,
                          @Nonnull AttributePolicy policy,
                          @Nonnull String builderName) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.builderName = Objects.requireNonNull(builderName, "builderName");
    }

    @Override
    @Nonnull
    public SpanHandle start() {
        // PA-0: заглушка; PA-1 подключит SpanSpecGovernance.validateAndNormalize(...)
        return runtime.startSpan(spec);
    }

    @Override
    public void run(@Nonnull Runnable action) {
        Objects.requireNonNull(action, "action");
        try (SpanHandle handle = start()) {
            action.run();
        }
    }

    @Override
    @Nonnull
    public <T> T call(@Nonnull Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        try (SpanHandle handle = start()) {
            return supplier.get();
        }
    }

    @Override
    @Nonnull
    public <T> T callChecked(@Nonnull ThrowingSupplier<T> supplier) throws Exception {
        Objects.requireNonNull(supplier, "supplier");
        try (SpanHandle handle = start()) {
            return supplier.get();
        }
    }
}
