package space.br1440.platform.tracing.otel.javaagent.control.state;

/**
 * Test/benchmark white-box bridge to package-private {@link EffectiveTracingPolicyDigest}.
 *
 * <p>Lives in {@code src/test/java} in the same package so it can see
 * package-private members without exports or reflection.
 */
public final class EffectiveTracingPolicyDigestAccess {

    private EffectiveTracingPolicyDigestAccess() {
    }

    /**
     * Delegates to {@code EffectiveTracingPolicyDigest.sha256(EffectiveTracingPolicy)}
     * using the policy carried inside the provided {@link AcceptedPolicyView}.
     *
     * <p>The view must be an {@link AcceptedPolicyEnvelope} — the only production
     * implementation that holds a typed {@code EffectiveTracingPolicy} reference.
     *
     * @param view snapshot obtained from {@code AcceptedPolicyAccess.current()}
     * @return lowercase-hex SHA-256 digest string (64 characters)
     * @throws IllegalArgumentException if {@code view} is not an {@link AcceptedPolicyEnvelope}
     */
    public static String sha256(AcceptedPolicyView view) {
        if (!(view instanceof AcceptedPolicyEnvelope envelope)) {
            throw new IllegalArgumentException(
                    "view must be AcceptedPolicyEnvelope, got: " + view.getClass().getName());
        }
        return EffectiveTracingPolicyDigest.sha256(envelope.policy());
    }
}
