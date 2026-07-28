package space.br1440.platform.tracing.autoconfigure.configuration;

/**
 * Thrown by {@link TracingConfigurationAuthorityGuard} on illegal authority/posture combinations.
 * This is a startup fail-fast signal, not a recoverable runtime exception.
 */
public final class TracingConfigurationGuardException extends RuntimeException {
    public TracingConfigurationGuardException(String message) {
        super(message);
    }
}
