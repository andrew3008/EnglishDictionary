package space.br1440.platform.tracing.otel.javaagent.bootstrap.failure;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxDenialReason;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxRegistrationAssessment;

public final class RemoteJmxRegistrationDeniedException extends IllegalStateException {

    private final RemoteJmxRegistrationAssessment denial;

    public RemoteJmxRegistrationDeniedException(RemoteJmxRegistrationAssessment denial) {
        super(Objects.requireNonNull(denial, "denial").denialDescription());

        if (denial.allowsLocalMutationRegistration()) {
            throw new IllegalArgumentException("Remote JMX assessment permits registration; nothing to deny");
        }

        this.denial = denial;
    }

    public RemoteJmxDenialReason failureCode() {
        return denial.failureCode();
    }

    public RemoteJmxRegistrationAssessment denial() {
        return denial;
    }
}
