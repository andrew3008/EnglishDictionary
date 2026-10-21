package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.PLATFORM_ACP_NOT_EXECUTED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.TRANSPORT_STALE;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapCleanupFailures;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapResources;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.JmxRegistrationException;
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

        Throwable cleanupFailure = BootstrapCleanupFailures.rollback(resources, cause);
        if (sectionFailure != null) {
            rethrowSectionFailure(sectionFailure);
        }

        BootstrapException effectiveFailure = Objects.requireNonNull(classified, "classified");
        try {
            if (cleanupFailure != null || JmxRegistrationException.rollbackIncompleteIn(cause)) {
                Throwable escalationCause = cleanupFailure == null ? cause : cleanupFailure;
                synchronized (lifecycle.monitor()) {
                    lifecycle.escalateRollbackIncomplete("activate", escalationCause);
                    journal.publishFailed(lifecycle.failure(), batchId, evidence);
                    effectiveFailure = new BootstrapException(lifecycle.failure());
                }
            }

            return effectiveFailure;
        } finally {
            PlatformThrowables.propagateIfFatal(cause);
            PlatformThrowables.propagateIfFatal(cleanupFailure);
        }
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

    void publishTransportStale(String batchId,
                               ExtensionRuntimeEvidence evidence) {
        IllegalStateException cause = new IllegalStateException(
                "JMX runtime controls are being deregistered"
        );

        synchronized (lifecycle.monitor()) {
            if (!lifecycle.active()) {
                return;
            }

            lifecycle.fail(TRANSPORT_STALE, "shutdown", "shutdown", null, cause);
            journal.publishFailed(lifecycle.failure(), batchId, evidence);
        }
    }

    void completeShutdown(String batchId,
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
            publishRollbackIncomplete("shutdown", terminalFailure, batchId, evidence);
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

    void completeCloseFailure(Throwable cleanupFailure,
                              String batchId,
                              ExtensionRuntimeEvidence evidence) {
        publishRollbackIncomplete("close", cleanupFailure, batchId, evidence);
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

    private void publishRollbackIncomplete(String phase,
                                           Throwable cause,
                                           String batchId,
                                           ExtensionRuntimeEvidence evidence) {
        synchronized (lifecycle.monitor()) {
            lifecycle.escalateRollbackIncomplete(phase, cause);
            if (lifecycle.state() == BootstrapState.FAILED) {
                journal.publishFailed(lifecycle.failure(), batchId, evidence);
            }
        }
    }

    private static void rethrowSectionFailure(Throwable sectionFailure) {
        if (sectionFailure instanceof Error error) {
            throw error;
        }

        throw (RuntimeException) sectionFailure;
    }
}
