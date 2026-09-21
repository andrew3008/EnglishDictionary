package space.br1440.platform.tracing.otel.javaagent.jmx.security;

import java.util.List;
import java.util.Objects;

public record RemoteJmxRegistrationAssessment(boolean allowsLocalMutationRegistration,
                                              RemoteJmxDenialReason failureCode,
                                              List<String> offendingArguments,
                                              String remediation) {

    public RemoteJmxRegistrationAssessment {
        Objects.requireNonNull(failureCode, "Remote JMX failure code must not be null");
        Objects.requireNonNull(remediation, "Remote JMX remediation must not be null");

        offendingArguments = List.copyOf(Objects.requireNonNull(
                offendingArguments,
                "Remote JMX offending arguments must not be null"
        ));

        if (allowsLocalMutationRegistration == failureCode.denies()) {
            throw new IllegalArgumentException(
                    "Remote JMX assessment must pair approval with failureCode=NONE and denial with a reason"
            );
        }

        if ((failureCode == RemoteJmxDenialReason.NONE) && (!offendingArguments.isEmpty() || !remediation.isEmpty())) {
            throw new IllegalArgumentException(
                    "Approved Remote JMX assessment must not report offending arguments or remediation"
            );
        }
    }

    public String denialDescription() {
        if (failureCode == RemoteJmxDenialReason.NONE) {
            return "";
        }

        return offendingArguments.isEmpty()
                ? "%s: %s".formatted(failureCode, remediation)
                : "%s: %s; detected=[%s]".formatted(failureCode, remediation, String.join(", ", offendingArguments));
    }
}
