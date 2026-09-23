package space.br1440.platform.tracing.otel.javaagent.bootstrap.verification;

final class VerificationFailures {

    private VerificationFailures() {
    }

    static VerificationException failure(VerificationFailureCode code, String message) {
        return new VerificationException(code, message);
    }

    static VerificationException failure(VerificationFailureCode code, String message, Throwable cause) {
        return new VerificationException(code, message, cause);
    }
}
