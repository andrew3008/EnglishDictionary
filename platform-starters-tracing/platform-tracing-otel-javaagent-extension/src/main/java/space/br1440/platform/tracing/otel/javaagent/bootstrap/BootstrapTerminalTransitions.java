package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.BootstrapFailureCodes.AGENT_STARTUP_FAILED;

import java.util.Objects;
import java.util.Optional;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.BootstrapFailure;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.BootstrapFailureLog;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.FailureAdmission;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.FailureSite;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapCleanupFailures;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;

final class BootstrapTerminalTransitions {

    private final BootstrapLifecycleState lifecycle;
    private final BootstrapReadinessJournal journal;

    BootstrapTerminalTransitions(BootstrapLifecycleState lifecycle,
                                 BootstrapReadinessJournal journal) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.journal = Objects.requireNonNull(journal, "journal");
    }

    FailureAdmission terminalize(FailureSite site,
                                 String failureCode,
                                 Throwable cause,
                                 String batchId,
                                 ExtensionRuntimeEvidence evidence) {
        Objects.requireNonNull(site, "site");

        synchronized (lifecycle.monitor()) {
            return project(lifecycle.fail(failureCode, site.phase(), cause), batchId, evidence);
        }
    }

    Optional<FailureAdmission> terminalizeStartup(Throwable cause, ExtensionRuntimeEvidence evidence) {
        synchronized (lifecycle.monitor()) {
            return lifecycle.failPrepared(AGENT_STARTUP_FAILED, FailureSite.startup().phase(), cause)
                    .map(admission -> project(admission, BatchIds.NONE, evidence));
        }
    }

    Optional<FailureAdmission> recordMissingPreparation(ExtensionRuntimeEvidence evidence) {
        IllegalStateException cause = new IllegalStateException(
                "AgentListener.afterAgent invoked without a prior ACP prepare() call"
        );

        synchronized (lifecycle.monitor()) {
            return lifecycle.recordMissingPreparation(cause)
                    .map(admission -> project(admission, BatchIds.NONE, evidence));
        }
    }

    FailureAdmission escalate(String phase,
                              Throwable cleanupFailure,
                              String batchId,
                              ExtensionRuntimeEvidence evidence) {
        synchronized (lifecycle.monitor()) {
            return project(lifecycle.escalateRollbackIncomplete(phase, cleanupFailure), batchId, evidence);
        }
    }

    void publishCloseFailureSnapshot(CloseOperation.CloseContext context,
                                     Throwable cleanupFailure) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(cleanupFailure, "cleanupFailure");

        synchronized (lifecycle.monitor()) {
            BootstrapFailure classified = BootstrapLifecycleState.classifyCloseFailure(context, cleanupFailure);
            publishCurrentFailure(classified);
        }
    }

    Throwable completeCloseFailure(CloseOperation.CloseContext context,
                                   Throwable cleanupFailure,
                                   Throwable priorPublicationFailure,
                                   boolean failureSnapshotPublished) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(cleanupFailure, "cleanupFailure");

        BootstrapFailure recordedFailure;
        Throwable baseOutcome;
        synchronized (lifecycle.monitor()) {
            if (priorPublicationFailure == null
                    && lifecycle.closeFailureAlreadyRecorded(cleanupFailure)) {
                return BootstrapCleanupFailures.aggregate(cleanupFailure, publishCapturing(lifecycle.failure()));
            }

            recordedFailure = lifecycle.recordCloseFailure(context, cleanupFailure);
            if (priorPublicationFailure != null) {
                BootstrapCleanupFailures.suppressPreservingPrimary(
                        recordedFailure.cause(),
                        priorPublicationFailure
                );
            }

            Throwable finalPublicationFailure = failureSnapshotPublished ? null : publishCapturing(recordedFailure);
            baseOutcome = BootstrapCleanupFailures.aggregate(cleanupFailure, priorPublicationFailure);
            baseOutcome = BootstrapCleanupFailures.aggregate(baseOutcome, finalPublicationFailure);
        }

        try {
            BootstrapFailureLog.closeFailed(recordedFailure, baseOutcome);
            return baseOutcome;
        } catch (RuntimeException | Error loggerFailure) {
            synchronized (lifecycle.monitor()) {
                BootstrapCleanupFailures.suppressPreservingPrimary(
                        lifecycle.failure().cause(),
                        loggerFailure
                );
            }

            return BootstrapCleanupFailures.aggregate(baseOutcome, loggerFailure);
        }
    }

    private FailureAdmission project(FailureAdmission admission,
                                     String batchId,
                                     ExtensionRuntimeEvidence evidence) {
        if (!admission.recorded()) {
            return admission;
        }

        try {
            journal.publishFailed(admission.failure(), batchId, evidence);
            return admission;
        } catch (RuntimeException | Error projectionFailure) {
            return admission.withProjectionFailure(projectionFailure);
        }
    }

    private Throwable publishCapturing(BootstrapFailure failure) {
        try {
            publishCurrentFailure(failure);
            return null;
        } catch (RuntimeException | Error publicationFailure) {
            BootstrapCleanupFailures.suppressPreservingPrimary(failure.cause(), publicationFailure);
            return publicationFailure;
        }
    }

    private void publishCurrentFailure(BootstrapFailure failure) {
        ExtensionReadinessWireSnapshot current = journal.current();
        journal.publishFailed(failure, current.batchId(), current.evidence());
    }
}
