package space.br1440.platform.tracing.autoconfigure.servlet.synthetic;

import space.br1440.platform.tracing.otel.propagation.OtelTraceparentReader;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

import io.opentelemetry.context.Context;

/** Синтетический нарушитель для self-test web-границ */
public final class WebBoundaryViolation {

    private Context context;
    private OtelTraceparentReader traceparentReader;
    private TracingRuntime runtime;
}
