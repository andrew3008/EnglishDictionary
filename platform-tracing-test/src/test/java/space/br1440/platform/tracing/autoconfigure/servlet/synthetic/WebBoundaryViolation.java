package space.br1440.platform.tracing.autoconfigure.servlet.synthetic;

import io.opentelemetry.context.Context;

import space.br1440.platform.tracing.core.propagation.control.TraceControlHeaderInjector;
import space.br1440.platform.tracing.core.runtime.TracingRuntime;

/** Синтетический нарушитель для self-test web-границ Slice I. */
public final class WebBoundaryViolation {

    private Context context;
    private TraceControlHeaderInjector injector;
    private TracingRuntime runtime;
}
