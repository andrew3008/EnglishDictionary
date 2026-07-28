package space.br1440.platform.tracing.autoconfigure.configuration;

/**
 * Ожидаемая временная недоступность утверждённых Agent read-only проекций.
 */
final class AcceptedTracingPolicyUnavailableException extends RuntimeException {

    AcceptedTracingPolicyUnavailableException(String message) {
        super(message);
    }

    AcceptedTracingPolicyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
