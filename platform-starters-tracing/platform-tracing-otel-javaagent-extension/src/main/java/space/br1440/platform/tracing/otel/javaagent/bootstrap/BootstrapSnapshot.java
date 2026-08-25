package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;

record BootstrapSnapshot(String lifecycle,
                         String[] installedCapabilities,
                         String failureCode,
                         String failureMessage,
                         String fingerprint,
                         String sessionId,
                         ExtensionReadinessWireSnapshot wire) {

    static BootstrapSnapshot initializing() {
        return new BootstrapSnapshot(
                "NEW", new String[0], "", "", "", "", ExtensionReadinessWireSnapshot.initializing()
        );
    }

    static BootstrapSnapshot initializing(AutoconfigureSession session, ExtensionReadinessWireSnapshot wire) {
        return new BootstrapSnapshot(
                BootstrapState.ACTIVATING.name(),
                session.readiness().capabilities(),
                "",
                "",
                session.fingerprint().value(),
                session.sessionId().toString(),
                wire
        );
    }

    static BootstrapSnapshot of(AutoconfigureSession session,
                                BootstrapState lifecycle,
                                String failureCode,
                                String failureMessage,
                                ExtensionReadinessWireSnapshot wire) {
        return new BootstrapSnapshot(
                lifecycle.name(),
                session.readiness().capabilities(),
                (failureCode == null) ? "" : failureCode,
                (failureMessage == null) ? "" : failureMessage,
                session.fingerprint().value(),
                session.sessionId().toString(),
                wire
        );
    }
}
