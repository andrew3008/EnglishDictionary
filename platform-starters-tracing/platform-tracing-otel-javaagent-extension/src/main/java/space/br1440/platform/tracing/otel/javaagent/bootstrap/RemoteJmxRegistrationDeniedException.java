package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxDenialReason;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxRegistrationAssessment;

final class RemoteJmxRegistrationDeniedException extends IllegalStateException {

    private final RemoteJmxRegistrationAssessment denial;

    RemoteJmxRegistrationDeniedException(RemoteJmxRegistrationAssessment denial) {
        super(Objects.requireNonNull(denial, "denial").denialDescription());

        if (denial.allowsLocalMutationRegistration()) {
            throw new IllegalArgumentException("Remote JMX assessment permits registration; nothing to deny");
        }

        this.denial = denial;
    }

    RemoteJmxDenialReason failureCode() {
        return denial.failureCode();
    }

    RemoteJmxRegistrationAssessment denial() {
        return denial;
    }
}
