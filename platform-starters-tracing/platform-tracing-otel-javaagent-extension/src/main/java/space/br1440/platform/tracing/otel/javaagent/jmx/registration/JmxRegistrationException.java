package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

public final class JmxRegistrationException extends RuntimeException {

    private final String failureCode;
    private boolean rollbackIncomplete;

    public JmxRegistrationException(String message, Throwable cause) {
        this(message, cause, null);
    }

    public JmxRegistrationException(String message, Throwable cause, String failureCode) {
        super(message, cause);
        this.failureCode = failureCode;
    }

    public String failureCode() {
        return failureCode;
    }

    public boolean rollbackIncomplete() {
        return rollbackIncomplete;
    }

    public static boolean rollbackIncompleteIn(Throwable failure) {
        if (failure == null) {
            return false;
        }

        if (markedRollbackIncomplete(failure)) {
            return true;
        }

        for (Throwable suppressed : failure.getSuppressed()) {
            if (markedRollbackIncomplete(suppressed)) {
                return true;
            }
        }

        return false;
    }

    static JmxRegistrationException classify(String failureCode, String message, RuntimeException failure) {
        JmxRegistrationException classified;
        if (failure instanceof JmxRegistrationException jmxFailure) {
            classified = new JmxRegistrationException(jmxFailure.getMessage(), jmxFailure.getCause(), failureCode);
            for (Throwable suppressed : jmxFailure.getSuppressed()) {
                classified.addSuppressed(suppressed);
            }
        } else {
            classified = new JmxRegistrationException(message, failure, failureCode);
        }

        if (rollbackIncompleteIn(failure)) {
            classified.markRollbackIncomplete();
        }

        return classified;
    }

    void markRollbackIncomplete() {
        rollbackIncomplete = true;
    }

    private static boolean markedRollbackIncomplete(Throwable failure) {
        return failure instanceof JmxRegistrationException jmxFailure
                && jmxFailure.rollbackIncomplete();
    }
}
