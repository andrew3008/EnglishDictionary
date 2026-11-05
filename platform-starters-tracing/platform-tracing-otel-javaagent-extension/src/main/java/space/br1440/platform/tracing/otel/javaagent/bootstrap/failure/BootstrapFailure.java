package space.br1440.platform.tracing.otel.javaagent.bootstrap.failure;

import java.util.Objects;

public record BootstrapFailure(String primaryCode, String effectiveCode, String phase, Throwable cause) {

    public BootstrapFailure {
        Objects.requireNonNull(primaryCode, "primaryCode");
        Objects.requireNonNull(effectiveCode, "effectiveCode");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(cause, "cause");
    }
}
