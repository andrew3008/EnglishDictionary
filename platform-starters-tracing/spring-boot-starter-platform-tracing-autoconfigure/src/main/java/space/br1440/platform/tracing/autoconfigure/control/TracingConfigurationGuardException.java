package space.br1440.platform.tracing.autoconfigure.control;

public final class TracingConfigurationGuardException extends RuntimeException {

    public TracingConfigurationGuardException(String message) {
        super(message);
    }

    public TracingConfigurationGuardException(String message, Throwable cause) {
        super(message, cause);
    }
}
