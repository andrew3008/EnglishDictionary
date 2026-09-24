package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionLifecycle;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadinessSnapshot;

final class BootstrapReadinessJournal {

    private final Object monitor;
    private final AtomicReference<ExtensionReadinessWireSnapshot> snapshot = new AtomicReference<>(
            ExtensionReadinessWireSnapshot.initializing()
    );

    BootstrapReadinessJournal(Object monitor) {
        this.monitor = Objects.requireNonNull(monitor, "monitor");
    }

    ExtensionReadinessWireSnapshot current() {
        return snapshot.get();
    }

    void publishInitializing(AutoconfigureSession activeSession) {
        snapshot.set(ExtensionReadinessWireSnapshot.initializing(
                activeSession.readiness().snapshot(),
                activeSession.sessionId().toString()
        ));
    }

    void publishReady(ExtensionReadinessWireSnapshot ready) {
        snapshot.set(Objects.requireNonNull(ready, "ready"));
    }

    void publishFailed(AutoconfigureSession activeSession, String failureCode) {
        ExtensionReadinessWireSnapshot current = snapshot.get();
        PlatformExtensionReadinessSnapshot readiness = activeSession.readiness().snapshot();
        boolean sameRuntimeEpoch = activeSession.sessionId().toString().equals(current.runtimeEpoch());
        String batchId = sameRuntimeEpoch ? current.batchId() : "";
        ExtensionRuntimeEvidence evidence = sameRuntimeEpoch
                ? current.evidence()
                : ExtensionRuntimeEvidence.unavailable(readiness.extensionVersion());
        publishFailed(activeSession, failureCode, batchId, evidence);
    }

    void publishFailed(AutoconfigureSession activeSession,
                       String failureCode,
                       String batchId,
                       ExtensionRuntimeEvidence evidence) {
        synchronized (monitor) {
            PlatformExtensionReadinessSnapshot readiness = activeSession.readiness().fail(failureCode);
            String runtimeEpoch = activeSession.sessionId().toString();
            ExtensionReadinessWireSnapshot current = snapshot.get();
            String effectiveFailureCode = readiness.failureCode();
            if (PlatformExtensionLifecycle.FAILED.name().equals(current.lifecycle())
                    && runtimeEpoch.equals(current.runtimeEpoch())
                    && effectiveFailureCode.equals(current.failureCode())) {
                return;
            }

            long evidenceGeneration = runtimeEpoch.equals(current.runtimeEpoch())
                    ? current.evidenceGeneration() + 1L
                    : 1L;

            snapshot.set(ExtensionReadinessWireSnapshot.failed(
                    readiness,
                    runtimeEpoch,
                    batchId,
                    evidenceGeneration,
                    evidence
            ));
        }
    }
}
