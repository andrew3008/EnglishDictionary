package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class JmxRegistrationExceptionFixtures {

    public static JmxRegistrationException rollbackIncomplete(String message, Throwable cause) {
        JmxRegistrationException failure = new JmxRegistrationException(message, cause);
        failure.markRollbackIncomplete();
        return failure;
    }
}
