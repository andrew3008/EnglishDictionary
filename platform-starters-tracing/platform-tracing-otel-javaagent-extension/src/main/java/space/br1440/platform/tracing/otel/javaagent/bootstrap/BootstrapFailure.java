package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;

record BootstrapFailure(String primaryCode, String effectiveCode, String phase, Throwable cause) {

    BootstrapFailure {
        Objects.requireNonNull(primaryCode, "primaryCode");
        Objects.requireNonNull(effectiveCode, "effectiveCode");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(cause, "cause");
    }
}
