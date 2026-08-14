package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

public final class JmxRegistrationException extends RuntimeException {

    public JmxRegistrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
