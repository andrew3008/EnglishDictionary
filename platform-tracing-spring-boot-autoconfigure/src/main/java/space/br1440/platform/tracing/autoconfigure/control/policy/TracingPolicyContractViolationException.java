package space.br1440.platform.tracing.autoconfigure.control.policy;

public final class TracingPolicyContractViolationException extends RuntimeException {

    public TracingPolicyContractViolationException(String message) {
        super(message);
    }

    public TracingPolicyContractViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
