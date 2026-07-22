package space.br1440.platform.tracing.otel.javaagent.exception;

public class TracingValidationException extends RuntimeException {

    public TracingValidationException(String message) {
        super(message);
    }
}
