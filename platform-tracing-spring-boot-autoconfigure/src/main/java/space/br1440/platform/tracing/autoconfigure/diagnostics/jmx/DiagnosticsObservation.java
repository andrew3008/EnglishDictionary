package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx;

import java.util.List;
import java.util.Map;

public record DiagnosticsObservation(String agentInstanceId,
                                     long invalidConfigCount,
                                     Map<String, Long> configReloadMetrics,
                                     List<String> configAuditTrail,
                                     Map<String, Long> safeWrapperMetrics,
                                     String platformLogLevel,
                                     boolean propagationEnabled) {
}
