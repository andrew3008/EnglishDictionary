package space.br1440.platform.tracing.api.synthetic;

import io.opentelemetry.context.Context;

import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

/** Синтетический нарушитель для self-test архитектурных правил Slice I. */
public final class ApiBoundaryViolation {

    private Context context;
    private TracingRuntime runtime;
}
