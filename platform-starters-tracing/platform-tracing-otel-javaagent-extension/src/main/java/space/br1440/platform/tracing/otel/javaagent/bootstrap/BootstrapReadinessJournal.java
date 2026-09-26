package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionLifecycle;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
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

    void publishInitializing(PlatformExtensionReadiness readiness, AgentInstanceId agentInstanceId) {
        snapshot.set(ExtensionReadinessWireSnapshot.initializing(
                readiness.snapshot(),
                agentInstanceId.toString()
        ));
    }

    void publishReady(ExtensionReadinessWireSnapshot ready) {
        snapshot.set(Objects.requireNonNull(ready, "ready"));
    }

    void publishFailed(PlatformExtensionReadiness readiness,
                       AgentInstanceId agentInstanceId,
                       String failureCode) {
        ExtensionReadinessWireSnapshot current = snapshot.get();
        PlatformExtensionReadinessSnapshot readinessSnapshot = readiness.snapshot();
        boolean sameAgentInstance = agentInstanceId.toString().equals(current.agentInstanceId());
        String batchId = sameAgentInstance ? current.batchId() : "";
        ExtensionRuntimeEvidence evidence = sameAgentInstance
                ? current.evidence()
                : ExtensionRuntimeEvidence.unavailable(readinessSnapshot.extensionVersion());
        publishFailed(readiness, agentInstanceId, failureCode, batchId, evidence);
    }

    void publishFailed(PlatformExtensionReadiness readiness,
                       AgentInstanceId agentInstanceId,
                       String failureCode,
                       String batchId,
                       ExtensionRuntimeEvidence evidence) {
        synchronized (monitor) {
            PlatformExtensionReadinessSnapshot readinessSnapshot = readiness.fail(failureCode);
            String agentInstanceIdValue = agentInstanceId.toString();
            ExtensionReadinessWireSnapshot current = snapshot.get();
            String effectiveFailureCode = readinessSnapshot.failureCode();
            if (PlatformExtensionLifecycle.FAILED.name().equals(current.lifecycle())
                    && agentInstanceIdValue.equals(current.agentInstanceId())
                    && effectiveFailureCode.equals(current.failureCode())) {
                return;
            }

            long evidenceGeneration = batchId.isEmpty()
                    ? 0L
                    : agentInstanceIdValue.equals(current.agentInstanceId())
                            ? current.evidenceGeneration() + 1L
                            : 1L;

            snapshot.set(ExtensionReadinessWireSnapshot.failed(
                    readinessSnapshot,
                    agentInstanceIdValue,
                    batchId,
                    evidenceGeneration,
                    evidence
            ));
        }
    }
}
