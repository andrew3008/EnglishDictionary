package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

public final class JmxRegistrationException extends RuntimeException {

    private boolean rollbackIncomplete;

    public JmxRegistrationException(String message, Throwable cause) {
        super(message, cause);
    }

    public boolean rollbackIncomplete() {
        return rollbackIncomplete;
    }

    void markRollbackIncomplete() {
        rollbackIncomplete = true;
    }
}
