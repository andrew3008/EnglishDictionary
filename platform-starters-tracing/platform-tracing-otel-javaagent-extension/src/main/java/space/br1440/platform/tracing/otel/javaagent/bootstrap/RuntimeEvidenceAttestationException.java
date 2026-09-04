package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;

final class RuntimeEvidenceAttestationException extends IllegalStateException {

    private final AttestationFailureCode code;

    RuntimeEvidenceAttestationException(AttestationFailureCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    RuntimeEvidenceAttestationException(AttestationFailureCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    AttestationFailureCode code() {
        return code;
    }
}
