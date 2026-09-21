package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.ObservationCapture;

public record ProcessorMetricsObservation(ObservationCapture capture,
                                          JmxOwnerState<ProcessorSpanTimeoutValues> spanTimeout,
                                          JmxOwnerState<ProcessorCompositeValues> composite,
                                          JmxOwnerState<ProcessorMetricsValues> metrics) {
}
