package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;

public record ProcessorMetricsObservation(String agentInstanceId,
                                          JmxOwnerState<ProcessorWatchdogValues> watchdog,
                                          JmxOwnerState<ProcessorCompositeValues> composite,
                                          JmxOwnerState<ProcessorMetricsValues> metrics) {
}
