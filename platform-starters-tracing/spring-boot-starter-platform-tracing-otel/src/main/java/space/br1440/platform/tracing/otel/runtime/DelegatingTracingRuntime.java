package space.br1440.platform.tracing.otel.runtime;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.otel.semconv.policy.AttributePolicy;

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

    @Override
    @Nonnull
    default CorrelationScope openRequestIdentityScope(@Nonnull String requestId, boolean forceTrace, boolean qaTrace) {
        return delegate().openRequestIdentityScope(requestId, forceTrace, qaTrace);
    }

    @Override
    @Nonnull
    default TraceControlBoundaryState currentTraceControlBoundary() {
        return delegate().currentTraceControlBoundary();
    }
}
