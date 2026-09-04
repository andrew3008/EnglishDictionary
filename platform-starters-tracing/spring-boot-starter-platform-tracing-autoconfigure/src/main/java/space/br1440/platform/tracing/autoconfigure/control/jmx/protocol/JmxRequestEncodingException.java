package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

public final class JmxRequestEncodingException extends RuntimeException {

    public JmxRequestEncodingException(String requestKind, Throwable cause) {
        super("Cannot encode platform JMX control request: " + requestKind, cause);
    }
}
