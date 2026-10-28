package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.PLATFORM_ACP_NOT_EXECUTED;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapCleanupFailures;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapResources;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.JmxRegistrationException;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.BootstrapLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

final class BootstrapTerminalTransitions {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(BootstrapTerminalTransitions.class);

    private final BootstrapLifecycleState lifecycle;
    private final BootstrapReadinessJournal journal;

    BootstrapTerminalTransitions(BootstrapLifecycleState lifecycle,
                                 BootstrapReadinessJournal journal) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.journal = Objects.requireNonNull(journal, "journal");
    }

    BootstrapException recordActivationFailure(String failureCode,
                                               String subphase,
                                               Throwable cause,
                                               String batchId,
                                               ExtensionRuntimeEvidence evidence) {
        return terminalizeFailure(
                failureCode,
                "activate",
                subphase,
                null,
                cause,
                batchId,
                evidence
        );
    }

    BootstrapException completeActivationFailure(BootstrapException classified,
                                                 BootstrapResources resources,
                                                 Throwable cause,
                                                 Throwable sectionFailure,
                                                 String batchId,
                                                 ExtensionRuntimeEvidence evidence) {
        if (Thread.holdsLock(lifecycle.monitor())) {
            throw new IllegalMonitorStateException(
                    "Activation rollback must run outside the bootstrap lifecycle monitor"
            );
        }

        if (sectionFailure != null) {
            BootstrapCleanupFailures.suppressPreservingPrimary(cause, sectionFailure);
        }

        Throwable cleanupFailure = (resources == null)
                ? null
                : BootstrapCleanupFailures.rollback(resources, cause);

        AutoconfigureSession detachedSession = lifecycle.detachFailedSession();
        if (detachedSession != null) {
            try {
                detachedSession.close();
            } catch (RuntimeException | Error sessionCleanupFailure) {
                BootstrapCleanupFailures.suppressPreservingPrimary(cause, sessionCleanupFailure);
                cleanupFailure = BootstrapCleanupFailures.aggregate(cleanupFailure, sessionCleanupFailure);
            }
        }

        Throwable activationOutcome = BootstrapCleanupFailures.aggregate(cause, sectionFailure);
        activationOutcome = BootstrapCleanupFailures.aggregate(activationOutcome, cleanupFailure);

        BootstrapException effectiveFailure = classified;
        try {
            if (cleanupFailure != null || JmxRegistrationException.rollbackIncompleteIn(cause)) {
                Throwable escalationCause = cleanupFailure == null ? cause : cleanupFailure;
                synchronized (lifecycle.monitor()) {
                    lifecycle.escalateRollbackIncomplete("activate", escalationCause);
                    journal.publishFailed(lifecycle.failure(), batchId, evidence);
                    effectiveFailure = new BootstrapException(lifecycle.failure());
                }
            }
        } catch (RuntimeException | Error settlementFailure) {
            activationOutcome = BootstrapCleanupFailures.aggregate(activationOutcome, settlementFailure);
            throw settlementFailure;
        } finally {
            PlatformThrowables.propagateIfFatal(activationOutcome);
        }

        if (sectionFailure != null) {
            rethrowSectionFailure(sectionFailure);
        }

        return Objects.requireNonNull(effectiveFailure, "classified");
    }

    BootstrapException terminalizePreparationFailure(String failureCode,
                                                     String subphase,
                                                     String propertyName,
                                                     Throwable cause,
                                                     ExtensionRuntimeEvidence evidence) {
        return terminalizeFailure(
                failureCode,
                "prepare",
                subphase,
                propertyName,
                cause,
                "",
                evidence
        );
    }

    Throwable completeCleanupFailure(String phase,
                                     Throwable primaryFailure,
                                     Throwable cleanupFailure,
                                     ExtensionRuntimeEvidence evidence) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(primaryFailure, "primaryFailure");
        Objects.requireNonNull(cleanupFailure, "cleanupFailure");
        Objects.requireNonNull(evidence, "evidence");

        Throwable outcome = BootstrapCleanupFailures.aggregate(primaryFailure, cleanupFailure);
        try {
            synchronized (lifecycle.monitor()) {
                lifecycle.escalateRollbackIncomplete(phase, cleanupFailure);
                journal.publishFailed(lifecycle.failure(), "", evidence);
                if (primaryFailure instanceof BootstrapException) {
                    return new BootstrapException(lifecycle.failure());
                }
            }

            return outcome;
        } catch (RuntimeException | Error settlementFailure) {
            outcome = BootstrapCleanupFailures.aggregate(outcome, settlementFailure);
            throw settlementFailure;
        } finally {
            PlatformThrowables.propagateIfFatal(outcome);
        }
    }

    BootstrapException terminalizeCustomizationFailure(String failureCode,
                                                       String phase,
                                                       Throwable cause,
                                                       ExtensionRuntimeEvidence evidence) {
        return terminalizeFailure(
                failureCode,
                phase,
                phase,
                null,
                cause,
                "",
                evidence
        );
    }

    void failMissingPreparation(ExtensionRuntimeEvidence evidence, Runnable diagnosticRegistration) {
        Objects.requireNonNull(diagnosticRegistration, "diagnosticRegistration");
        BootstrapException classified;
        synchronized (lifecycle.monitor()) {
            if (lifecycle.state() != BootstrapState.NEW) {
                return;
            }

            IllegalStateException cause = new IllegalStateException(
                    "AgentListener.afterAgent invoked without a prior ACP prepare() call"
            );
            classified = terminalizeFailure(
                    PLATFORM_ACP_NOT_EXECUTED,
                    "activate",
                    "activate",
                    null,
                    cause,
                    "",
                    evidence
            );
        }

        try {
            diagnosticRegistration.run();
        } catch (RuntimeException | Error diagnosticFailure) {
            BootstrapCleanupFailures.suppressPreservingPrimary(classified.failure().cause(), diagnosticFailure);
            PlatformThrowables.propagateIfFatal(diagnosticFailure);
        }

        throw classified;
    }

    void publishCloseFailureSnapshot(BootstrapLifecycleState.CloseContext context,
                                     Throwable cleanupFailure) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(cleanupFailure, "cleanupFailure");

        synchronized (lifecycle.monitor()) {
            BootstrapFailure classified = lifecycle.classifyCloseFailure(context, cleanupFailure);
            publishCurrentFailure(classified);
        }
    }

    Throwable completeCloseFailure(BootstrapLifecycleState.CloseContext context,
                                   Throwable cleanupFailure,
                                   Throwable priorPublicationFailure,
                                   boolean failureSnapshotPublished) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(cleanupFailure, "cleanupFailure");

        BootstrapFailure settledFailure;
        Throwable finalPublicationFailure = null;
        Throwable baseOutcome;
        synchronized (lifecycle.monitor()) {
            if (priorPublicationFailure == null
                    && lifecycle.closeFailureAlreadyRecorded(cleanupFailure)) {
                try {
                    publishCurrentFailure(lifecycle.failure());
                    return cleanupFailure;
                } catch (RuntimeException | Error publicationFailure) {
                    BootstrapCleanupFailures.suppressPreservingPrimary(
                            lifecycle.failure().cause(),
                            publicationFailure
                    );
                    return BootstrapCleanupFailures.aggregate(cleanupFailure, publicationFailure);
                }
            }

            settledFailure = lifecycle.recordCloseFailure(context, cleanupFailure);
            if (priorPublicationFailure != null) {
                BootstrapCleanupFailures.suppressPreservingPrimary(
                        settledFailure.cause(),
                        priorPublicationFailure
                );
            }

            if (!failureSnapshotPublished) {
                try {
                    publishCurrentFailure(settledFailure);
                } catch (RuntimeException | Error publicationFailure) {
                    finalPublicationFailure = publicationFailure;
                    BootstrapCleanupFailures.suppressPreservingPrimary(
                            settledFailure.cause(),
                            publicationFailure
                    );
                }
            }

            baseOutcome = BootstrapCleanupFailures.aggregate(cleanupFailure, priorPublicationFailure);
            baseOutcome = BootstrapCleanupFailures.aggregate(baseOutcome, finalPublicationFailure);
        }

        Throwable outcomeToLog = baseOutcome;
        try {
            log.log(BootstrapLogEvent.BOOTSTRAP_PHASE_FAILED, fields -> fields
                    .putString(AgentLogField.FAILURE_CODE, settledFailure.effectiveCode())
                    .putString(AgentLogField.PHASE, "close")
                    .putString(AgentLogField.SUBPHASE, "close")
                    .putString(
                            AgentLogField.RESOURCE_LABEL,
                            BootstrapCleanupFailures.resourceLabel(outcomeToLog)
                    )
                    .cause(outcomeToLog));
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

    private BootstrapException terminalizeFailure(String failureCode,
                                                  String phase,
                                                  String subphase,
                                                  String propertyName,
                                                  Throwable cause,
                                                  String batchId,
                                                  ExtensionRuntimeEvidence evidence) {
        synchronized (lifecycle.monitor()) {
            BootstrapException classified = lifecycle.fail(
                    failureCode,
                    phase,
                    subphase,
                    propertyName,
                    cause
            );
            if (lifecycle.state() == BootstrapState.FAILED) {
                journal.publishFailed(lifecycle.failure(), batchId, evidence);
            }
            return classified;
        }
    }

    private void publishCurrentFailure(BootstrapFailure failure) {
        ExtensionReadinessWireSnapshot current = journal.current();
        journal.publishFailed(failure, current.batchId(), current.evidence());
    }

    private static void rethrowSectionFailure(Throwable sectionFailure) {
        if (sectionFailure instanceof Error error) {
            throw error;
        }

        throw (RuntimeException) sectionFailure;
    }
}
