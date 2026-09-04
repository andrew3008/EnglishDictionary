package space.br1440.platform.tracing.autoconfigure.control.settings.read;

public record AcceptedPolicySummary(boolean samplingEnabled,
                                    double samplingRatio,
                                    int samplingRouteRatiosCount,
                                    boolean validationEnabled,
                                    boolean validationStrict,
                                    boolean exporterEnabled,
                                    boolean traceControlHeadersEnabled,
                                    String diagnosticsLogLevel) {
}
