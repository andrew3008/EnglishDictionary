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

        String baseMessage = "Platform bootstrap failure [%s] during phase '%s'"
                .formatted(failure.code(), failure.phase());
        if (failure.cause() == null) {
            return baseMessage;
        }

        String causeMessage = failure.cause().toString();
        return "%s: %s".formatted(baseMessage, causeMessage);
    }
}
