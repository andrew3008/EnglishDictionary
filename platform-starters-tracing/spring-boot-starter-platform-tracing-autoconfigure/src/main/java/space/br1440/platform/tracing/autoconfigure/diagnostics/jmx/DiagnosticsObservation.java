package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx;

import java.util.Map;

public record DiagnosticsObservation(String agentInstanceId,
                                     long invalidConfigCount,
                                     Map<String, Long> safeWrapperMetrics) {
}
