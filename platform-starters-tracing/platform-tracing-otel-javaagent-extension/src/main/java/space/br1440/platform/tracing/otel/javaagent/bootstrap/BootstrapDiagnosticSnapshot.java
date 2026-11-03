package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;

record BootstrapDiagnosticSnapshot(BootstrapState state, BootstrapFailure failure) {

    BootstrapDiagnosticSnapshot {
        Objects.requireNonNull(state, "state");
    }
}
