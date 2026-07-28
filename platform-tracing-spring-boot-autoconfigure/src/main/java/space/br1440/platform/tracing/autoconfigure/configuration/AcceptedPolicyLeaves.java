package space.br1440.platform.tracing.autoconfigure.configuration;

/**
 * Accepted policy leaf values truth category. Secret-safe: route ratios not included.
 */
public record AcceptedPolicyLeaves(
        boolean samplingEnabled,
        double samplingRatio,
        int samplingRouteRatiosCount,
        boolean validationEnabled,
        boolean validationStrict,
        boolean exporterEnabled,
        boolean traceControlHeadersEnabled,
        String diagnosticsLogLevel
) {}
