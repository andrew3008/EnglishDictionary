package space.br1440.platform.tracing.core.runtime;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;

/**
 * Маркер для декораторов {@link TracingRuntime}, которые полностью делегируют поведение.
 * <p>
 * Реализации по умолчанию делегируют каждый новый метод SPI в {@link #delegate()},
 * чтобы существующим подклассам-декораторам (например, {@code MeteredTracingRuntime})
 * не требовались шаблонные переопределения для сквозных (pass-through) методов.
 */
public interface DelegatingTracingRuntime extends TracingRuntime {

    @Nonnull
    TracingRuntime delegate();

    @Override
    @Nonnull
    default AttributePolicy attributePolicy() {
        return delegate().attributePolicy();
    }
}
