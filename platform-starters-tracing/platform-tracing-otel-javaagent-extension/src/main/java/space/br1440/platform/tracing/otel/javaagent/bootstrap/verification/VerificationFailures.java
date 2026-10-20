package space.br1440.platform.tracing.otel.javaagent.bootstrap.verification;

import lombok.experimental.UtilityClass;

@UtilityClass
final class VerificationFailures {

    static VerificationException failure(VerificationFailureCode code, String message) {
        return new VerificationException(code, message);
    }

    static VerificationException failure(VerificationFailureCode code, String message, Throwable cause) {
        return new VerificationException(code, message, cause);
    }
}
