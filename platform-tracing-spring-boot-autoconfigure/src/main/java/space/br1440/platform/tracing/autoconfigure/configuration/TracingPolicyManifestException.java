package space.br1440.platform.tracing.autoconfigure.configuration;

final class TracingPolicyManifestException extends IllegalStateException {

    TracingPolicyManifestException(String message) {
        super(message);
    }

    TracingPolicyManifestException(String message, Throwable cause) {
        super(message, cause);
    }
}
