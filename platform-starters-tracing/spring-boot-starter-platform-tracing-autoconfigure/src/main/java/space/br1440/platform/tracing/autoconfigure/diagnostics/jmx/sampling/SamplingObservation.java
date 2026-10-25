package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.sampling;

import java.util.Map;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;

public record SamplingObservation(String agentInstanceId,
                                  boolean samplerEnabled,
                                  double samplingRatio,
                                  long routeRatioCount,
                                  String configLastUpdatedSource,
                                  JmxOwnerState<Map<String, Long>> decisionCounts) {
}
