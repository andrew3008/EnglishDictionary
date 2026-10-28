package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.ACTIVATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.PLATFORM_SAMPLER_REPLACED;

import java.util.Objects;
import java.util.UUID;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapResources;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.verification.VerificationFailureCode;
import space.br1440.platform.tracing.otel.javaagent.diagnostics.PlatformLogControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.JmxRegistrationException;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxDenialReason;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxRegistrationAssessment;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.BootstrapLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.propagation.PlatformPropagationGate;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionLifecycle;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadinessSnapshot;

import io.opentelemetry.sdk.trace.SdkTracerProvider;

final class BootstrapActivation {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(BootstrapActivation.class);

    private static final String INSTALLATION_PROFILE = "platform-agent-secure-v1";
    private static final int INSTALLATION_PROTOCOL_VERSION = 1;
    private static final long INITIAL_EVIDENCE_GENERATION = 1L;

    private final BootstrapLifecycleState lifecycle;
    private final BootstrapReadinessJournal journal;
    private final BootstrapTerminalTransitions terminalTransitions;

    BootstrapActivation(BootstrapLifecycleState lifecycle,
                        BootstrapReadinessJournal journal,
                        BootstrapTerminalTransitions terminalTransitions) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.terminalTransitions = Objects.requireNonNull(terminalTransitions, "terminalTransitions");
    }

    void activate(SdkTracerProvider tracerProvider, Runnable installLifecycleShutdownHook) {
        Objects.requireNonNull(tracerProvider, "tracerProvider");
        Objects.requireNonNull(installLifecycleShutdownHook, "installLifecycleShutdownHook");

        ActivationAttempt attempt = null;
        BootstrapResources resources = null;
        ActivationFailure failure = null;
        ExtensionRuntimeEvidence fallbackEvidence = journal.current().evidence();
        synchronized (lifecycle.monitor()) {
            AutoconfigureSession session = lifecycle.beginActivation();
            try {
                resources = new BootstrapResources();
                resources.push(session, "autoconfigure-session");
                lifecycle.attachResources(resources);
                installLifecycleShutdownHook.run();
                attempt = new ActivationAttempt(session, resources, tracerProvider);
            } catch (RuntimeException | Error cause) {
                failure = recordPrologueFailure(cause, fallbackEvidence);
            }

            if (failure == null) {
                failure = runPhases(attempt);
            }
        }

        if (failure == null) {
            return;
        }

        String batchId = attempt == null ? "" : attempt.batchId();
        ExtensionRuntimeEvidence evidence = attempt == null ? fallbackEvidence : attempt.evidence();
        throw terminalTransitions.completeActivationFailure(
                failure.classified(),
                resources,
                failure.cause(),
                failure.sectionFailure(),
                batchId,
                evidence
        );
    }

    private ActivationFailure recordPrologueFailure(Throwable cause, ExtensionRuntimeEvidence evidence) {
        String failureCode = cause instanceof LinkageError
                ? VerificationFailureCode.EXTENSION_LINKAGE_ERROR.name()
                : ACTIVATION_FAILED;
        return recordFailure(failureCode, "activation-prologue", cause, "", evidence);
    }

    private ActivationFailure runPhases(ActivationAttempt attempt) {
        try {
            requireSessionSampler(attempt);
            assessRemoteJmx(attempt);
            bindRuntime(attempt);
            publishReady(attempt);
            return null;
        } catch (RuntimeException | Error cause) {
            return recordFailure(attempt, cause);
        }
    }

    private ActivationFailure recordFailure(ActivationAttempt attempt, Throwable cause) {
        return recordFailure(
                failureCodeOf(attempt, cause),
                attempt.phase().subphase(),
                cause,
                attempt.batchId(),
                attempt.evidence()
        );
    }

    private ActivationFailure recordFailure(String failureCode,
                                             String subphase,
                                             Throwable cause,
                                             String batchId,
                                             ExtensionRuntimeEvidence evidence) {
        try {
            BootstrapException classified = terminalTransitions.recordActivationFailure(
                    failureCode,
                    subphase,
                    cause,
                    batchId,
                    evidence
            );
            return ActivationFailure.classified(classified, cause);
        } catch (RuntimeException | Error sectionFailure) {
            return ActivationFailure.unclassified(cause, sectionFailure);
        }
    }

    private static void requireSessionSampler(ActivationAttempt attempt) {
        attempt.enter(ActivationPhase.SAMPLER_POSTCONDITION);

        //noinspection resource -- сессия принадлежит попытке активации, закрывает её BootstrapResources
        AutoconfigureSession session = attempt.session();
        //noinspection resource -- SdkTracerProvider принадлежит autoconfigure SDK и закрывается им, а не активацией
        if (attempt.tracerProvider().getSampler() != session.sampler()) {
            throw new IllegalStateException("Installed SDK sampler is not the platform session sampler");
        }
        session.readiness().markInstalled(PlatformExtensionCapability.PLATFORM_SAMPLER_INSTALLED);
    }

    private static void assessRemoteJmx(ActivationAttempt attempt) {
        attempt.enter(ActivationPhase.REMOTE_JMX_ASSESSMENT);

        //noinspection resource -- сессия принадлежит попытке активации, закрывает её BootstrapResources
        AutoconfigureSession session = attempt.session();
        RemoteJmxRegistrationAssessment assessment = session.remoteJmxMutationRegistrationAssessor().assess();
        if (assessment.allowsLocalMutationRegistration()) {
            return;
        }

        log.log(BootstrapLogEvent.REMOTE_JMX_MUTATION_REGISTRATION_DENIED, fields -> fields
                .putString(AgentLogField.FAILURE_CODE, assessment.failureCode().name())
                .putString(AgentLogField.REMEDIATION, assessment.remediation())
                .putString(AgentLogField.OFFENDING_ARGUMENTS, String.join(",", assessment.offendingArguments())));
        throw new RemoteJmxRegistrationDeniedException(assessment);
    }

    private void bindRuntime(ActivationAttempt attempt) {
        attempt.enter(ActivationPhase.RUNTIME_BINDING);

        //noinspection resource -- сессия принадлежит попытке активации, закрывает её BootstrapResources
        AutoconfigureSession session = attempt.session();
        //noinspection resource -- runtime-регистрации принадлежат BootstrapResources, lifecycle hook — BootstrapCoordinator
        BootstrapResources resources = attempt.resources();
        resources.push(PlatformPropagationGate.shared().bindAcceptedPolicy(session.policy()), "propagation-gate");
        resources.push(PlatformLogControl.shared().bindAcceptedPolicy(session.policy()), "log-control");

        PlatformTracingMBeanRegistrar.RuntimeRegistration runtimeRegistration = session.jmxRegistrar().registerRuntimeControls(session.policy());
        attempt.recordRuntimeRegistration(runtimeRegistration.batchId());
        resources.push(runtimeRegistration, "runtime-jmx-controls");
    }

    private void publishReady(ActivationAttempt attempt) {
        attempt.enter(ActivationPhase.READINESS_PUBLICATION);

        //noinspection resource -- сессия принадлежит попытке активации, закрывает её BootstrapResources
        AutoconfigureSession session = attempt.session();
        if (!attempt.evidence().complete()) {
            throw new IllegalStateException("Runtime attestation evidence is incomplete");
        }
        session.scrubbingRuntime().requireInstallationComplete();
        session.readiness().markInstalled(PlatformExtensionCapability.SANITIZER_INSTALLED);
        PlatformExtensionReadinessSnapshot.Ready readiness = session.readiness().completeInitialization();

        ExtensionReadinessWireSnapshot ready = ExtensionReadinessWireSnapshot.ready(
                readiness,
                session.agentInstanceId().toString(),
                attempt.batchId(),
                INITIAL_EVIDENCE_GENERATION,
                attempt.evidence()
        );
        journal.publishReady(ready);
        lifecycle.commitActive();
        emitInstalled(ready);
    }

    private static String failureCodeOf(ActivationAttempt attempt, Throwable failure) {
        return switch (failure) {
            case LinkageError ignored -> VerificationFailureCode.EXTENSION_LINKAGE_ERROR.name();
            case RemoteJmxRegistrationDeniedException denied -> denied.failureCode().name();
            case JmxRegistrationException registration when (registration.failureCode() != null) -> registration.failureCode();
            default -> attempt.phase().failureCode();
        };
    }

    private static void emitInstalled(ExtensionReadinessWireSnapshot ready) {
        log.log(BootstrapLogEvent.EXTENSION_INSTALLED, fields -> fields
                .putEnum(AgentLogField.LIFECYCLE, PlatformExtensionLifecycle.READY)
                .putString(AgentLogField.PROFILE, INSTALLATION_PROFILE)
                .putLong(AgentLogField.PROTOCOL_VERSION, INSTALLATION_PROTOCOL_VERSION)
                .putString(AgentLogField.EXTENSION_VERSION, ready.evidence().extensionVersion()));
    }

    private enum ActivationPhase {
        SAMPLER_POSTCONDITION(PLATFORM_SAMPLER_REPLACED, "sampler-postcondition"),
        REMOTE_JMX_ASSESSMENT(
                RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN.name(),
                "remote-jmx-assessment"
        ),
        RUNTIME_BINDING(ACTIVATION_FAILED, "runtime-binding"),
        READINESS_PUBLICATION(ACTIVATION_FAILED, "readiness-publication");

        private final String failureCode;
        private final String subphase;

        ActivationPhase(String failureCode, String subphase) {
            this.failureCode = failureCode;
            this.subphase = subphase;
        }

        String failureCode() {
            return failureCode;
        }

        String subphase() {
            return subphase;
        }
    }

    private record ActivationFailure(BootstrapException classified, Throwable cause, Throwable sectionFailure) {

        static ActivationFailure classified(BootstrapException classified, Throwable cause) {
            return new ActivationFailure(Objects.requireNonNull(classified, "classified"), cause, null);
        }

        static ActivationFailure unclassified(Throwable cause, Throwable sectionFailure) {
            return new ActivationFailure(null, cause, Objects.requireNonNull(sectionFailure, "sectionFailure"));
        }
    }

    private static final class ActivationAttempt {

        private static final String NO_BATCH_ID = "";

        private final AutoconfigureSession session;
        private final BootstrapResources resources;
        private final SdkTracerProvider tracerProvider;
        private final ExtensionRuntimeEvidence evidence;

        private ActivationPhase phase = ActivationPhase.SAMPLER_POSTCONDITION;
        private String batchId = NO_BATCH_ID;

        ActivationAttempt(AutoconfigureSession session,
                          BootstrapResources resources,
                          SdkTracerProvider tracerProvider) {
            this.session = Objects.requireNonNull(session, "session");
            this.resources = Objects.requireNonNull(resources, "resources");
            this.tracerProvider = Objects.requireNonNull(tracerProvider, "tracerProvider");
            this.evidence = session.evidence();
        }

        void enter(ActivationPhase next) {
            this.phase = Objects.requireNonNull(next, "next");
        }

        void recordRuntimeRegistration(UUID registeredBatchId) {
            this.batchId = Objects.requireNonNull(registeredBatchId, "registeredBatchId").toString();
        }

        AutoconfigureSession session() {
            return session;
        }

        BootstrapResources resources() {
            return resources;
        }

        SdkTracerProvider tracerProvider() {
            return tracerProvider;
        }

        ActivationPhase phase() {
            return phase;
        }

        String batchId() {
            return batchId;
        }

        ExtensionRuntimeEvidence evidence() {
            return evidence;
        }
    }
}
