package space.br1440.platform.tracing.api.span.synthetic;

import space.br1440.platform.tracing.otel.propagation.OtelTraceparentReader;

/** Синтетический нарушитель SpanFactory boundary для self-test Slice I. */
public final class SpanFactoryReaderViolation {

    private OtelTraceparentReader reader;
}
