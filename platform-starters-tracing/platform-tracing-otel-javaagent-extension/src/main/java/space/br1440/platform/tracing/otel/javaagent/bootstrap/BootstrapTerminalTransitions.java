package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.FAILED_ROLLBACK_INCOMPLETE;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.TRANSPORT_STALE;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapCleanupFailures;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapResources;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

final class BootstrapTerminalTransitions {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(BootstrapTerminalTransitions.class);

    private final BootstrapLifecycleState lifecycle;
    private final BootstrapReadinessJournal journal;
    private final DiagnosticReadinessHandle diagnosticHandle;

    BootstrapTerminalTransitions(BootstrapLifecycleState lifecycle,
                                 BootstrapReadinessJournal journal,
                                 DiagnosticReadinessHandle diagnosticHandle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.diagnosticHandle = Objects.requireNonNull(diagnosticHandle, "diagnosticHandle");
    }

    BootstrapException terminalizeActivationFailure(AutoconfigureSession activeSession,
                                                    BootstrapResources resources,
                                                    String failureCode,
                                                    Throwable cause,
                                                    String batchId,
                                                    ExtensionRuntimeEvidence evidence) {
        Throwable cleanupFailure = BootstrapCleanupFailures.rollback(resources, cause);
        boolean rollbackIncomplete = cleanupFailure != null
                || FAILED_ROLLBACK_INCOMPLETE.equals(activeSession.readiness().failureCode());
        String effectiveFailureCode = rollbackIncomplete ? FAILED_ROLLBACK_INCOMPLETE : failureCode;
        BootstrapException classified = lifecycle.fail(effectiveFailureCode, "activate", cause);
        if (rollbackIncomplete) {
            activeSession.readiness().failTerminalCleanup(FAILED_ROLLBACK_INCOMPLETE, cause);
        }

        journal.publishFailed(activeSession, effectiveFailureCode, cause, batchId, evidence);
        PlatformThrowables.propagateIfFatal(cause);
        PlatformThrowables.propagateIfFatal(cleanupFailure);
        return classified;
    }

    void publishTransportStale(AutoconfigureSession activeSession,
                               String batchId,
                               ExtensionRuntimeEvidence evidence) {
        IllegalStateException cause = new IllegalStateException(
                "JMX runtime controls are being deregistered"
        );

        synchronized (lifecycle.monitor()) {
            if (!lifecycle.active()) {
                return;
            }

            lifecycle.markFailed(TRANSPORT_STALE, "shutdown", cause);
            journal.publishFailed(activeSession, TRANSPORT_STALE, cause, batchId, evidence);
        }
    }

    void completeShutdown(AutoconfigureSession activeSession,
                          String batchId,
                          ExtensionRuntimeEvidence evidence,
                          Throwable cleanupFailure) {
        Throwable terminalFailure = cleanupFailure;
        Throwable diagnosticCleanupFailure = null;
        try {
            diagnosticHandle.close();
        } catch (RuntimeException | Error failure) {
            diagnosticCleanupFailure = failure;
            terminalFailure = BootstrapCleanupFailures.aggregate(terminalFailure, failure);
        }

        if (terminalFailure == null) {
            return;
        }

        publishRollbackIncomplete(activeSession, "shutdown", terminalFailure, batchId, evidence);
        log.warn(
                AgentEventCode.BOOT_CLASSIFIED_FAILURE,
                "Platform bootstrap shutdown cleanup failed",
                terminalFailure,
                "phase",
                "shutdown"
        );

        if (diagnosticCleanupFailure != null) {
            BootstrapCleanupFailures.rethrow(terminalFailure);
        }
    }

    void completeCloseFailure(Throwable cleanupFailure) {
        synchronized (lifecycle.monitor()) {
            AutoconfigureSession activeSession = lifecycle.currentSession();
            if (activeSession == null) {
                lifecycle.markFailed(FAILED_ROLLBACK_INCOMPLETE, "close", cleanupFailure);
                return;
            }

            ExtensionReadinessWireSnapshot current = journal.current();
            publishRollbackIncomplete(
                    activeSession,
                    "close",
                    cleanupFailure,
                    current.batchId(),
                    current.evidence()
            );
        }
    }

    void publishSessionFailure(AutoconfigureSession failedSession, String failureCode, Throwable cause) {
        if (failedSession != null) {
            journal.publishFailed(failedSession, failureCode, cause);
        }
    }

    private void publishRollbackIncomplete(AutoconfigureSession activeSession,
                                           String phase,
                                           Throwable cause,
                                           String batchId,
                                           ExtensionRuntimeEvidence evidence) {
        lifecycle.markFailed(FAILED_ROLLBACK_INCOMPLETE, phase, cause);
        activeSession.readiness().failTerminalCleanup(FAILED_ROLLBACK_INCOMPLETE, cause);
        journal.publishFailed(activeSession, FAILED_ROLLBACK_INCOMPLETE, cause, batchId, evidence);
    }
}
