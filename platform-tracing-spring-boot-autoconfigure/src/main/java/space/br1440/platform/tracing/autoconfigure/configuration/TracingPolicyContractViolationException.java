package space.br1440.platform.tracing.autoconfigure.configuration;

/**
 * Thrown when a codec, schema, protocol, or programming contract is violated.
 * This is a fail-fast signal, not a recoverable delivery failure.
 * Must NOT be caught and converted to {@link TracingPolicyDeliveryResult#FAILED}.
 */
public final class TracingPolicyContractViolationException extends RuntimeException {
    public TracingPolicyContractViolationException(String message) {
        super(message);
    }
    public TracingPolicyContractViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
