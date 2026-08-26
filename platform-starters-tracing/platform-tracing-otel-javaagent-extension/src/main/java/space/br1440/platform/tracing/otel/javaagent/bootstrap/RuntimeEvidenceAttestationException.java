package space.br1440.platform.tracing.otel.javaagent.bootstrap;

final class RuntimeEvidenceAttestationException extends IllegalStateException {

    private final String code;

    RuntimeEvidenceAttestationException(String code, String message) {
        super(message);
        this.code = code;
    }

    RuntimeEvidenceAttestationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    String code() {
        return code;
    }
}
