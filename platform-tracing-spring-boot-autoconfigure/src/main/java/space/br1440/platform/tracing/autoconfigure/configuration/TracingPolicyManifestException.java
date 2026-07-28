package space.br1440.platform.tracing.autoconfigure.configuration;

/**
 * Thrown when a complete eight-leaf Category A manifest cannot be built.
 * Initial occurrence blocks application readiness.
 * Runtime occurrence results in REJECTED outcome and LKG retention.
 */
public final class TracingPolicyManifestException extends RuntimeException {
    public TracingPolicyManifestException(String message) {
        super(message);
    }
}
