package space.br1440.platform.tracing.otel.javaagent.bootstrap;

/**
 * Отказ в допуске операции bootstrap ({@code REENTRANT_OR_OUT_OF_ORDER}, {@code SECOND_SDK_BUILD},
 * {@code DUPLICATE_ACTIVATION}).
 *
 * <p>Отклонённый вызов не меняет состояние lifecycle и не владеет ресурсами. Replay уже записанного
 * отказа выражается {@link BootstrapException}, а не этим типом.
 */
final class BootstrapAdmissionRejectedException extends RuntimeException {

    private final BootstrapFailure failure;

    BootstrapAdmissionRejectedException(BootstrapFailure failure) {
        super(BootstrapException.message(failure), failure.cause());
        this.failure = failure;
    }

    BootstrapFailure failure() {
        return failure;
    }

    String primaryCode() {
        return failure.primaryCode();
    }
}
