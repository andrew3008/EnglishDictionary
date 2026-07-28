package space.br1440.platform.tracing.autoconfigure.configuration;

/**
 * Apply diagnostics truth category. Separate from policy values and agent readiness.
 */
public record TracingApplyDiagnostics(
        String policyDigest,
        String latestApplyOutcome
) {}
