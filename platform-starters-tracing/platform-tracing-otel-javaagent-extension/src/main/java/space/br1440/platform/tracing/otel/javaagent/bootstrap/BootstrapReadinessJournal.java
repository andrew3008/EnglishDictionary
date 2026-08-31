package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionLifecycle;

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
                activeSession.readiness(),
                activeSession.sessionId().toString()
        ));
    }

    void publishReady(ExtensionReadinessWireSnapshot ready) {
        snapshot.set(Objects.requireNonNull(ready, "ready"));
    }

    void publishFailed(AutoconfigureSession activeSession, String failureCode, Throwable cause) {
        ExtensionReadinessWireSnapshot current = snapshot.get();
        publishFailed(activeSession, failureCode, cause, current.batchId(), current.evidence());
    }

    void publishFailed(AutoconfigureSession activeSession,
                       String failureCode,
                       Throwable cause,
                       String batchId,
                       ExtensionRuntimeEvidence evidence) {
        synchronized (monitor) {
            activeSession.readiness().fail(failureCode, cause);
            String runtimeEpoch = activeSession.sessionId().toString();
            ExtensionReadinessWireSnapshot current = snapshot.get();
            String effectiveFailureCode = activeSession.readiness().failureCode();
            if (PlatformExtensionLifecycle.FAILED.name().equals(current.lifecycle())
                    && runtimeEpoch.equals(current.runtimeEpoch())
                    && effectiveFailureCode.equals(current.failureCode())) {
                return;
            }

            long evidenceGeneration = runtimeEpoch.equals(current.runtimeEpoch())
                    ? current.evidenceGeneration() + 1L
                    : 1L;

            snapshot.set(ExtensionReadinessWireSnapshot.failed(
                    activeSession.readiness(),
                    runtimeEpoch,
                    batchId,
                    evidenceGeneration,
                    effectiveFailureCode,
                    evidence
            ));
        }
    }
}
