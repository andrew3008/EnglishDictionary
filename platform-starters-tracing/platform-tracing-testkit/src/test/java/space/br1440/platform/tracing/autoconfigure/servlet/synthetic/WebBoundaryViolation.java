package space.br1440.platform.tracing.autoconfigure.servlet.synthetic;

import io.opentelemetry.context.Context;

import space.br1440.platform.tracing.otel.propagation.OtelTraceparentReader;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

/** Синтетический нарушитель для self-test web-границ Slice I. */
public final class WebBoundaryViolation {

    private Context context;
    private OtelTraceparentReader traceparentReader;
    private TracingRuntime runtime;
}
