package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxDenialReason;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxRegistrationAssessment;

final class RemoteJmxRegistrationDeniedException extends IllegalStateException {

    private final RemoteJmxDenialReason failureCode;

    RemoteJmxRegistrationDeniedException(RemoteJmxRegistrationAssessment denial) {
        super(Objects.requireNonNull(denial, "denial").denialDescription());

        if (denial.allowsLocalMutationRegistration()) {
            throw new IllegalArgumentException("Remote JMX assessment permits registration; nothing to deny");
        }

        this.failureCode = denial.failureCode();
    }

    RemoteJmxDenialReason failureCode() {
        return failureCode;
    }
}
