package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

public final class JmxWireTypeViolationException extends RuntimeException {

    public static final String FAILURE_CODE = "JMX_WIRE_TYPE_VIOLATION";

    JmxWireTypeViolationException(String message) {
        super(message, null, false, false);
    }

    JmxWireTypeViolationException(String message, Throwable cause) {
        super(message, cause, false, false);
    }

    public String failureCode() {
        return FAILURE_CODE;
    }
}
