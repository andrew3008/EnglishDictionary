package space.br1440.platform.tracing.autoconfigure.configuration;

import java.util.Optional;

/**
 * Apply diagnostics truth category. Separate from policy values and agent readiness.
 * <p>
 * {@code policyDigest} and {@code latestApplyOutcome} are sourced exclusively from
 * the {@code applySnapshot} response (PRIV-W01). They are <strong>not</strong>
 * available via the {@code readAcceptedComplete} (PROPOSED_PRIV_R07) read path.
 * <p>
 * Use {@link #absent()} when assembling a projection from the complete-read path.
 * The {@code Optional.empty()} values signal structural absence — i.e., the
 * information is not exposed by the current protocol — rather than a fault.
 * <p>
 * HTTP rendering contract:
 * <pre>
 *   absent()  → {"available": false}
 *   fromApply → {"available": true, "policyDigest": "...", "latestApplyOutcome": "..."}
 * </pre>
 */
public record TracingApplyDiagnostics(
        Optional<String> policyDigest,
        Optional<String> latestApplyOutcome
) {
    /**
     * Structural absence: diagnostics are not exposed by the PROPOSED_PRIV_R07
     * complete-read path. Both fields are empty.
     * Use instead of placeholder strings such as "n/a" or "unknown".
     */
    public static TracingApplyDiagnostics absent() {
        return new TracingApplyDiagnostics(Optional.empty(), Optional.empty());
    }

    /**
     * Diagnostics sourced from a completed {@code applySnapshot} (PRIV-W01) response.
     */
    public static TracingApplyDiagnostics fromApply(String policyDigest, String latestApplyOutcome) {
        return new TracingApplyDiagnostics(
                Optional.ofNullable(policyDigest),
                Optional.ofNullable(latestApplyOutcome));
    }

    /**
     * @return {@code true} when both fields are empty (complete-read path, not apply response).
     */
    public boolean isAbsent() {
        return policyDigest.isEmpty() && latestApplyOutcome.isEmpty();
    }
}
