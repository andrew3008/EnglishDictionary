package space.br1440.platform.tracing.otel.javaagent.jmx.security;

import java.util.Objects;
import java.util.Set;

/**
 * Неизменяемый результат fail-closed проверки remote JMX.
 */
public record RemoteJmxExposureAssessment(
        RemoteJmxExposureState state,
        boolean allowsLocalMutationRegistration,
        boolean allowsRemoteObservation,
        String failureCode,
        boolean stickyFailure,
        Set<String> evidence) {

    public RemoteJmxExposureAssessment {
        Objects.requireNonNull(state, "Remote JMX exposure state must not be null");
        Objects.requireNonNull(failureCode, "Remote JMX failure code must not be null");
        evidence = Set.copyOf(Objects.requireNonNull(evidence, "Remote JMX evidence must not be null"));
        if (allowsRemoteObservation && !allowsLocalMutationRegistration) {
            throw new IllegalArgumentException("Remote observation requires an approved local control plane");
        }
        if (allowsLocalMutationRegistration && !"NONE".equals(failureCode)) {
            throw new IllegalArgumentException("Approved Remote JMX assessment must have failureCode=NONE");
        }
        if (!allowsLocalMutationRegistration && "NONE".equals(failureCode)) {
            throw new IllegalArgumentException("Denied Remote JMX assessment must provide a failure code");
        }
    }

    RemoteJmxExposureAssessment asStickyFailure() {
        if (stickyFailure) {
            return this;
        }
        return new RemoteJmxExposureAssessment(
                state,
                allowsLocalMutationRegistration,
                allowsRemoteObservation,
                failureCode,
                true,
                evidence);
    }
}
