package space.br1440.platform.tracing.autoconfigure.control.policy.read;

public record AcceptedPolicyLeaves(boolean samplingEnabled,
                                   double samplingRatio,
                                   int samplingRouteRatiosCount,
                                   boolean validationEnabled,
                                   boolean validationStrict,
                                   boolean exporterEnabled,
                                   boolean traceControlHeadersEnabled,
                                   String diagnosticsLogLevel) {
}
