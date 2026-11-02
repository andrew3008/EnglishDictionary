package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;

/** Согласованный снимок lifecycle; failure отсутствует, пока отказ не записан. */
record BootstrapDiagnosticSnapshot(BootstrapState state, BootstrapFailure failure) {

    BootstrapDiagnosticSnapshot {
        Objects.requireNonNull(state, "state");
    }
}
