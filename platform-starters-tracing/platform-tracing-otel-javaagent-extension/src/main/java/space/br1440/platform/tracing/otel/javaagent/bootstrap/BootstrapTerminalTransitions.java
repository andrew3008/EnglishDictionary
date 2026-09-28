package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.FAILED_ROLLBACK_INCOMPLETE;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.TRANSPORT_STALE;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapCleanupFailures;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapResources;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.BootstrapLogEvent;
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
                                                    String subphase,
                                                    Throwable cause,
                                                    String batchId,
                                                    ExtensionRuntimeEvidence evidence) {
        Throwable cleanupFailure = BootstrapCleanupFailures.rollback(resources, cause);
        boolean rollbackIncomplete = cleanupFailure != null
                || FAILED_ROLLBACK_INCOMPLETE.equals(
                        activeSession.readiness().snapshot().failureCode()
                );
        String effectiveFailureCode = rollbackIncomplete ? FAILED_ROLLBACK_INCOMPLETE : failureCode;
        BootstrapException classified = lifecycle.fail(effectiveFailureCode, "activate", subphase, null, cause);
        if (rollbackIncomplete) {
            activeSession.readiness().recordTerminalCleanupFailure(FAILED_ROLLBACK_INCOMPLETE);
        }

        journal.publishFailed(
                activeSession.readiness(),
                activeSession.agentInstanceId(),
                effectiveFailureCode,
                batchId,
                evidence
        );
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
            journal.publishFailed(
                    activeSession.readiness(),
                    activeSession.agentInstanceId(),
                    TRANSPORT_STALE,
                    batchId,
                    evidence
            );
        }
    }

    void completeShutdown(AutoconfigureSession activeSession,
                          String batchId,
                          ExtensionRuntimeEvidence evidence,
                          Throwable cleanupFailure) {
        Throwable terminalFailure = cleanupFailure;
        Throwable completionFailure = null;
        try {
            diagnosticHandle.close();
        } catch (RuntimeException | Error failure) {
            Throwable labeledFailure = BootstrapCleanupFailures.withResourceLabel(
                    "diagnostic-readiness",
                    failure
            );
            terminalFailure = BootstrapCleanupFailures.aggregate(terminalFailure, labeledFailure);
            completionFailure = labeledFailure;
        }

        if (terminalFailure == null) {
            return;
        }

        try {
            publishRollbackIncomplete(activeSession, "shutdown", terminalFailure, batchId, evidence);
        } catch (RuntimeException | Error failure) {
            terminalFailure = BootstrapCleanupFailures.aggregate(terminalFailure, failure);
            completionFailure = BootstrapCleanupFailures.aggregate(completionFailure, failure);
        }

        if (completionFailure != null) {
            BootstrapCleanupFailures.rethrow(terminalFailure);
        }

        Throwable failureToLog = terminalFailure;
        log.log(BootstrapLogEvent.BOOTSTRAP_SHUTDOWN_CLEANUP_FAILED, fields -> fields
                .putString(AgentLogField.PHASE, "shutdown")
                .putString(AgentLogField.RESOURCE_LABEL, BootstrapCleanupFailures.resourceLabel(failureToLog))
                .cause(failureToLog));
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

    void publishSessionFailure(AutoconfigureSession failedSession, String failureCode) {
        if (failedSession != null) {
            journal.publishFailed(
                    failedSession.readiness(),
                    failedSession.agentInstanceId(),
                    failureCode
            );
        }
    }

    private void publishRollbackIncomplete(AutoconfigureSession activeSession,
                                           String phase,
                                           Throwable cause,
                                           String batchId,
                                           ExtensionRuntimeEvidence evidence) {
        lifecycle.markFailed(FAILED_ROLLBACK_INCOMPLETE, phase, cause);
        activeSession.readiness().recordTerminalCleanupFailure(FAILED_ROLLBACK_INCOMPLETE);
        journal.publishFailed(
                activeSession.readiness(),
                activeSession.agentInstanceId(),
                FAILED_ROLLBACK_INCOMPLETE,
                batchId,
                evidence
        );
    }
}
