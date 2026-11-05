package space.br1440.platform.tracing.otel.javaagent.bootstrap.failure;

public final class BootstrapAdmissionRejectedException extends RuntimeException {

    private final BootstrapFailure failure;

    public BootstrapAdmissionRejectedException(BootstrapFailure failure) {
        super(BootstrapException.message(failure), failure.cause());
        this.failure = failure;
    }

    public BootstrapFailure failure() {
        return failure;
    }

    public String primaryCode() {
        return failure.primaryCode();
    }
}
