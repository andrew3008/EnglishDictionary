package space.br1440.platform.tracing.otel.javaagent.bootstrap.failure;

import java.util.Objects;

public final class BootstrapException extends RuntimeException {

    private final BootstrapFailure failure;

    public BootstrapException(BootstrapFailure failure) {
        super(message(failure), failure.cause());
        this.failure = failure;
    }

    public BootstrapFailure failure() {
        return failure;
    }

    static String message(BootstrapFailure failure) {
        Objects.requireNonNull(failure, "failure");

        String baseMessage = "Platform bootstrap failure [%s] during phase '%s'"
                .formatted(failure.effectiveCode(), failure.phase());

        return "%s: %s".formatted(baseMessage, failure.cause());
    }
}
