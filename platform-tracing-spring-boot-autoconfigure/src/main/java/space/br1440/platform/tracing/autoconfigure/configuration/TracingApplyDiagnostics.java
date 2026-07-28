package space.br1440.platform.tracing.autoconfigure.configuration;

/**
 * Apply diagnostics truth category. Separate from policy values and agent readiness.
 * <p>
 * {@code policyDigest} and {@code latestApplyOutcome} are sourced exclusively from
 * the {@code applySnapshot} response (PRIV-W01). They are NOT available via
 * the {@code readAcceptedComplete} (PRIV-R07) read path.
 * <p>
 * Use {@link #notAvailableViaCompleteRead()} when assembling a projection from
 * the complete-read path. This makes the absence explicit and
 * contract-correct rather than silently returning stub strings.
 */
public record TracingApplyDiagnostics(
        String policyDigest,
        String latestApplyOutcome
) {
    /**
     * Sentinel value indicating that apply diagnostics are structurally absent
     * on the complete-read path (PRIV-R07) and were NOT fabricated.
     * Callers rendering this to HTTP MUST emit a structured absent field,
     * not the literal string values.
     */
    public static final String ABSENT = "NOT_EXPOSED_BY_CURRENT_PROTOCOL";

    /**
     * Returns a diagnostics instance that explicitly marks both fields as
     * absent on the complete-read path. Do NOT use placeholder strings
     * such as "n/a" or "unknown" — use this factory method.
     */
    public static TracingApplyDiagnostics notAvailableViaCompleteRead() {
        return new TracingApplyDiagnostics(ABSENT, ABSENT);
    }

    /**
     * @return {@code true} if this instance represents an explicitly-absent
     *         diagnostic (sourced from complete-read path, not from apply response).
     */
    public boolean isAbsent() {
        return ABSENT.equals(policyDigest) && ABSENT.equals(latestApplyOutcome);
    }
}
