package space.br1440.platform.tracing.core.runtime;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;

/**
 * Marker for {@link TracingRuntime} decorators that fully delegate behavior.
 * <p>
 * Default implementations delegate each new SPI method to {@link #delegate()} so that
 * existing decorator subclasses (e.g. {@code MeteredTracingRuntime}) do not require
 * boilerplate overrides for pass-through methods.
 */
public interface DelegatingTracingRuntime extends TracingRuntime {

    @Nonnull
    TracingRuntime delegate();

    /**
     * Delegates to the underlying runtime. Decorators that override attribute policy
     * semantics must provide their own {@code @Override}.
     */
    @Override
    @Nonnull
    default AttributePolicy attributePolicy() {
        return delegate().attributePolicy();
    }
}
