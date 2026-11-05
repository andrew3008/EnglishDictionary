package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.BootstrapFailure;

record BootstrapDiagnosticSnapshot(BootstrapState state, BootstrapFailure failure) {

    BootstrapDiagnosticSnapshot {
        Objects.requireNonNull(state, "state");
    }
}
