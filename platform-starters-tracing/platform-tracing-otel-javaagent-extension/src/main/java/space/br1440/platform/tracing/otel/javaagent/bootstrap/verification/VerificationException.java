package space.br1440.platform.tracing.otel.javaagent.bootstrap.verification;

import java.util.Objects;

public final class VerificationException extends IllegalStateException {

    private final VerificationFailureCode code;

    VerificationException(VerificationFailureCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    VerificationException(VerificationFailureCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public VerificationFailureCode code() {
        return code;
    }
}
