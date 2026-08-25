package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;

final class BootstrapException extends RuntimeException {

    private final BootstrapFailure failure;

    BootstrapException(BootstrapFailure failure) {
        super(message(failure), failure.cause());
        this.failure = failure;
    }

    BootstrapFailure failure() {
        return failure;
    }

    private static String message(BootstrapFailure failure) {
        Objects.requireNonNull(failure, "failure");

        String causeMessage = (failure.cause() == null) ? "no recorded cause" : failure.cause().toString();
        return "Platform bootstrap failure [%s] during phase '%s': %s"
                .formatted(failure.code(), failure.phase(), causeMessage);
    }
}
