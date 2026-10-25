package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionLifecycle;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadinessSnapshot;

final class BootstrapReadinessJournal {

    private static final String FAILED_LIFECYCLE = PlatformExtensionLifecycle.FAILED.name();
    private static final long NO_EVIDENCE_GENERATION = 0L;

    private final Object monitor;
    private final PlatformExtensionReadiness readiness;
    private final String agentInstanceId;

    private volatile ExtensionReadinessWireSnapshot snapshot = ExtensionReadinessWireSnapshot.initializing();

    BootstrapReadinessJournal(Object monitor,
                              PlatformExtensionReadiness readiness,
                              AgentInstanceId agentInstanceId) {
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.agentInstanceId = Objects.requireNonNull(agentInstanceId, "agentInstanceId").toString();
    }

    ExtensionReadinessWireSnapshot current() {
        return snapshot;
    }

    void publishInitializing() {
        PlatformExtensionReadinessSnapshot observed = readiness.snapshot();
        if (!(observed instanceof PlatformExtensionReadinessSnapshot.Initializing initializing)) {
            throw new IllegalStateException("INITIALIZING readiness publication observed " + observed);
        }

        snapshot = ExtensionReadinessWireSnapshot.initializing(initializing, agentInstanceId);
    }

    void publishReady(ExtensionReadinessWireSnapshot ready) {
        snapshot = Objects.requireNonNull(ready, "ready");
    }

    void publishFailed(BootstrapFailure failure, String batchId, ExtensionRuntimeEvidence evidence) {
        requireMonitorHeld();
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(evidence, "evidence");

        ExtensionReadinessWireSnapshot current = snapshot;
        if (alreadyPublished(current, failure)) {
            return;
        }

        boolean escalation = isOwnFailure(current);
        String publishedBatchId = escalation ? current.batchId() : batchId;
        ExtensionRuntimeEvidence publishedEvidence = escalation ? current.evidence() : evidence;

        PlatformExtensionReadinessSnapshot.Failed failed = readiness.fail(failure.primaryCode(), failure.effectiveCode());
        publishedEvidence = evidenceForFailedReadiness(failed, publishedEvidence);
        snapshot = ExtensionReadinessWireSnapshot.failed(
                failed,
                agentInstanceId,
                publishedBatchId,
                nextEvidenceGeneration(current, publishedBatchId),
                publishedEvidence
        );
    }

    private static ExtensionRuntimeEvidence evidenceForFailedReadiness(PlatformExtensionReadinessSnapshot.Failed failed,
                                                                       ExtensionRuntimeEvidence evidence) {
        if (evidence.complete() || failed.extensionVersion().equals(evidence.extensionVersion())) {
            return evidence;
        }

        return ExtensionRuntimeEvidence.unavailable(failed.extensionVersion());
    }

    private void requireMonitorHeld() {
        if (!Thread.holdsLock(monitor)) {
            String message = "FAILED readiness publication requires the bootstrap lifecycle monitor";
            throw new IllegalMonitorStateException(message);
        }
    }

    private boolean alreadyPublished(ExtensionReadinessWireSnapshot current, BootstrapFailure failure) {
        return isOwnFailure(current)
                && failure.primaryCode().equals(current.primaryFailureCode())
                && failure.effectiveCode().equals(current.failureCode());
    }

    private boolean isOwnFailure(ExtensionReadinessWireSnapshot current) {
        return isOwnSnapshot(current) && FAILED_LIFECYCLE.equals(current.lifecycle());
    }

    private boolean isOwnSnapshot(ExtensionReadinessWireSnapshot current) {
        return agentInstanceId.equals(current.agentInstanceId());
    }

    private long nextEvidenceGeneration(ExtensionReadinessWireSnapshot current, String publishedBatchId) {
        if (publishedBatchId.isEmpty()) {
            return NO_EVIDENCE_GENERATION;
        }

        return current.evidenceGeneration() + 1L;
    }
}
