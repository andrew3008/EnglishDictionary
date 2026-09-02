package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import lombok.experimental.UtilityClass;

@UtilityClass
final class AttestationFailures {

    static RuntimeEvidenceAttestationException failure(AttestationFailureCode code, String message) {
        return new RuntimeEvidenceAttestationException(code, message);
    }

    static RuntimeEvidenceAttestationException failure(AttestationFailureCode code,
                                                       String message,
                                                       Throwable cause) {
        return new RuntimeEvidenceAttestationException(code, message, cause);
    }
}
