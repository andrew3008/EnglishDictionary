package pa0.consumer;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.spec.SpanExecution;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.span.DefaultSpanFactory;

/**
 * Positive compile probe: operational ABI не требует runtime/policy на каждом вызове.
 */
final class SafeBridgeConsumer {

    void useOperationalAbi(@Nonnull SpanSpec spec) {
        var runtime = NoOpTracingRuntime.noop();
        var factory = new DefaultSpanFactory(runtime, runtime.attributePolicy());

        SpanExecution execution = factory.fromSpec(spec);
        execution.start();

        factory.operation("portfolio.recalculate").child().start();
    }
}
