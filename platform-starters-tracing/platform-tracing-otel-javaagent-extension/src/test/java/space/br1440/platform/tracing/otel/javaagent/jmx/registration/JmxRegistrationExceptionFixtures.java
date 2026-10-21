package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

public final class JmxRegistrationExceptionFixtures {

    private JmxRegistrationExceptionFixtures() {
    }

    public static JmxRegistrationException rollbackIncomplete(String message, Throwable cause) {
        JmxRegistrationException failure = new JmxRegistrationException(message, cause);
        failure.markRollbackIncomplete();
        return failure;
    }
}
