package space.br1440.platform.tracing.autoconfigure.configuration;

/**
 * Secret-safe read projection of accepted tracing policy for Actuator exposure.
 * <p>
 * Does NOT expose raw route ratio maps, trace-control header values, or secrets.
 * All values are safe for inclusion in Actuator diagnostic output.
 */
public record AcceptedTracingPolicyView(
        String runtimeInstanceId,
        long acceptedSequence,
        String policyDigest,
        boolean samplingEnabled,
        double samplingRatio,
        int samplingRouteRatiosCount,
        boolean validationEnabled,
        boolean validationStrict,
        boolean exporterEnabled,
        boolean traceControlHeadersEnabled,
        String diagnosticsLogLevel,
        boolean agentReady,
        String latestApplyOutcome
) {}
