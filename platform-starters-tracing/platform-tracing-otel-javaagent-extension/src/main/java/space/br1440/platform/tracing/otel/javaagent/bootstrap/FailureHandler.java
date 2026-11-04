package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.AGENT_STARTUP_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.PLATFORM_ACP_NOT_EXECUTED;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapCleanupFailures;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.JmxRegistrationException;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

final class FailureHandler {

    private final BootstrapLifecycleState lifecycle;
    private final BootstrapTerminalTransitions terminalTransitions;
    private final Runnable diagnosticRegistration;

    FailureHandler(BootstrapLifecycleState lifecycle,
                   BootstrapTerminalTransitions terminalTransitions,
                   Runnable diagnosticRegistration) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.terminalTransitions = Objects.requireNonNull(terminalTransitions, "terminalTransitions");
        this.diagnosticRegistration = Objects.requireNonNull(diagnosticRegistration, "diagnosticRegistration");
    }

    BootstrapFailure handle(FailureSite site,
                            String failureCode,
                            Throwable cause,
                            AutoconfigureSession ownedSession,
                            ExtensionRuntimeEvidence evidence) {
        requireMonitorNotHeld(cause);
        FailureAdmission admission = terminalTransitions.terminalize(
                site,
                failureCode,
                cause,
                BatchIds.NONE,
                evidence
        );
        return completeCore(new FailureContext(
                site,
                failureCode,
                cause,
                admission,
                ownedSession,
                null,
                BatchIds.NONE,
                evidence
        ));
    }

    void handleStartup(Throwable cause, ExtensionRuntimeEvidence evidence) {
        requireMonitorNotHeld(cause);
        terminalTransitions.terminalizeStartup(cause, evidence).ifPresent(admission ->
                completeCore(new FailureContext(
                        FailureSite.startup(),
                        AGENT_STARTUP_FAILED,
                        cause,
                        admission,
                        null,
                        null,
                        BatchIds.NONE,
                        evidence
                )));
    }

    Optional<BootstrapFailure> handleMissingPreparation(ExtensionRuntimeEvidence evidence) {
        return terminalTransitions.recordMissingPreparation(evidence).map(admission ->
                complete(new FailureContext(
                        FailureSite.activation("activate"),
                        PLATFORM_ACP_NOT_EXECUTED,
                        admission.failure().cause(),
                        admission,
                        null,
                        null,
                        BatchIds.NONE,
                        evidence
                )));
    }

    BootstrapFailure complete(FailureContext context) {
        requireMonitorNotHeld(context.cause());
        return completeCore(context);
    }

    private BootstrapFailure completeCore(FailureContext context) {
        FailureAdmission admission = context.admission();
        Throwable cause = context.cause();
        SecondaryFailures secondary = new SecondaryFailures(admission.failure().cause(), cause);
        secondary.noteFatal(admission.projectionFailure());

        Throwable resourcesCleanupFailure = context.resources() == null
                ? null
                : BootstrapCleanupFailures.capture(context.resources()::close);
        //noinspection resource -- session закрывается через capture в этом методе
        AutoconfigureSession session = context.ownedSession() != null
                ? context.ownedSession()
                : admission.claimedSession();
        Throwable sessionCleanupFailure = session == null ? null : BootstrapCleanupFailures.capture(session::close);

        Throwable cleanupFailure = BootstrapCleanupFailures.aggregate(resourcesCleanupFailure, sessionCleanupFailure);
        secondary.noteFatal(cleanupFailure);

        boolean rollbackIncomplete = cleanupFailure != null || JmxRegistrationException.rollbackIncompleteIn(cause);
        secondary.suppress(admission.projectionFailure());
        secondary.suppress(cleanupFailure);

        FailureAdmission escalation = null;
        Throwable escalationCause = cleanupFailure == null ? cause : cleanupFailure;
        if (rollbackIncomplete) {
            escalation = terminalTransitions.escalate(
                    context.site().phase(),
                    escalationCause,
                    context.batchId(),
                    context.evidence()
            );
            secondary.record(escalation.projectionFailure());
        }

        if (admission.recorded()) {
            try {
                diagnosticRegistration.run();
            } catch (RuntimeException | Error registrationFailure) {
                secondary.record(registrationFailure);
            }
        }

        logFailure(context, escalation, escalationCause, secondary);
        if (secondary.firstFatal() != null) {
            BootstrapCleanupFailures.rethrow(secondary.firstFatal());
        }

        if (escalation != null && escalation.kind() != FailureAdmission.Kind.REJECTED) {
            return escalation.failure();
        }

        return admission.failure();
    }

    private void requireMonitorNotHeld(Throwable failure) {
        if (Thread.holdsLock(lifecycle.monitor())) {
            IllegalMonitorStateException violation = new IllegalMonitorStateException(
                    "Bootstrap failure handling must run outside the bootstrap lifecycle monitor"
            );
            violation.addSuppressed(failure);
            throw violation;
        }
    }

    private static void logFailure(FailureContext context,
                                   FailureAdmission escalation,
                                   Throwable escalationCause,
                                   SecondaryFailures secondary) {
        FailureSite site = context.site();
        Throwable cause = context.cause();
        FailureAdmission admission = context.admission();
        List<Runnable> emissions = new ArrayList<>(3);
        if (cause instanceof RemoteJmxRegistrationDeniedException denied) {
            emissions.add(() -> BootstrapFailureLog.remoteJmxDenied(denied.denial()));
        }

        if (admission.recorded()) {
            emissions.add(() -> BootstrapFailureLog.phaseFailed(site, admission.failure()));
        } else {
            emissions.add(() -> BootstrapFailureLog.rejected(site, context.failureCode(), cause));
        }

        if (escalation != null) {
            switch (escalation.kind()) {
                case RECORDED -> emissions.add(() -> BootstrapFailureLog.escalated(
                        escalation.failure(),
                        escalationCause
                ));
                case REJECTED -> emissions.add(() -> BootstrapFailureLog.rejected(escalation.failure()));
                case ALREADY_FAILED -> {
                    // незавершённый rollback уже зафиксирован и залогирован его владельцем
                }
            }
        }

        for (Runnable emission : emissions) {
            try {
                emission.run();
            } catch (RuntimeException | Error loggingFailure) {
                secondary.record(loggingFailure);
                if (PlatformThrowables.isFatal(loggingFailure)) {
                    break;
                }
            }
        }
    }
}
