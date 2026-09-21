package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.ACTIVATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.DIAGNOSTIC_MBEAN_REGISTRATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.RUNTIME_EVIDENCE_ATTESTATION_FAILED;

import java.util.Objects;
import java.util.UUID;

import space.br1440.platform.tracing.otel.javaagent.diagnostics.PlatformLogControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxDenialReason;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxRegistrationAssessment;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.propagation.PlatformPropagationGate;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;

final class BootstrapActivation {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(BootstrapActivation.class);

    private static final String SHUTDOWN_HOOK_THREAD_NAME = "platform-tracing-bootstrap-shutdown";
    private static final long INITIAL_EVIDENCE_GENERATION = 1L;

    private final BootstrapLifecycleState lifecycle;
    private final BootstrapReadinessJournal journal;
    private final BootstrapTerminalTransitions terminalTransitions;
    private final DiagnosticReadinessHandle diagnosticHandle;
    private final RuntimeEvidenceAttestor runtimeEvidenceAttestor;
    private final ShutdownHookRegistry hookRegistry;

    BootstrapActivation(BootstrapLifecycleState lifecycle,
                        BootstrapReadinessJournal journal,
                        BootstrapTerminalTransitions terminalTransitions,
                        DiagnosticReadinessHandle diagnosticHandle,
                        RuntimeEvidenceAttestor runtimeEvidenceAttestor,
                        ShutdownHookRegistry hookRegistry) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.terminalTransitions = Objects.requireNonNull(terminalTransitions, "terminalTransitions");
        this.diagnosticHandle = Objects.requireNonNull(diagnosticHandle, "diagnosticHandle");
        this.runtimeEvidenceAttestor = Objects.requireNonNull(runtimeEvidenceAttestor, "runtimeEvidenceAttestor");
        this.hookRegistry = Objects.requireNonNull(hookRegistry, "hookRegistry");
    }

    void activate() {
        synchronized (lifecycle.monitor()) {
            activateExclusively();
        }
    }

    private void activateExclusively() {
        AutoconfigureSession session = lifecycle.beginActivation();
        journal.publishInitializing(session);

        BootstrapResources resources = new BootstrapResources(hookRegistry);
        lifecycle.attachResources(resources);

        ActivationAttempt attempt = new ActivationAttempt(session, resources);
        try {
            registerDiagnosticReadiness(attempt);
            assessRemoteJmx(attempt);
            attestRuntimeEvidence(attempt);
            bindRuntime(attempt);
            publishReady(attempt);
        } catch (RuntimeException | Error failure) {
            throw terminalize(attempt, failure);
        }
    }

    private void registerDiagnosticReadiness(ActivationAttempt attempt) {
        attempt.enter(ActivationPhase.DIAGNOSTIC_REGISTRATION);

        diagnosticHandle.replace(
                attempt.session().jmxRegistrar().registerDiagnosticReadiness(journal::current)
        );
    }

    private void assessRemoteJmx(ActivationAttempt attempt) {
        attempt.enter(ActivationPhase.REMOTE_JMX_ASSESSMENT);

        RemoteJmxRegistrationAssessment assessment = attempt.session().remoteJmxMutationRegistrationAssessor().assess();
        if (assessment.allowsLocalMutationRegistration()) {
            return;
        }

        log.error(
                AgentEventCodes.BOOT_REMOTE_JMX_DENIED,
                "Remote JMX mutation registration denied",
                "failureCode", assessment.failureCode().name(),
                "remediation", assessment.remediation(),
                "offendingArguments", String.join(",", assessment.offendingArguments())
        );
        throw new RemoteJmxRegistrationDeniedException(assessment);
    }

    private void attestRuntimeEvidence(ActivationAttempt attempt) {
        attempt.enter(ActivationPhase.RUNTIME_EVIDENCE_ATTESTATION);

        String extensionVersion = attempt.session().readiness().extensionVersion();
        ExtensionRuntimeEvidence evidence = runtimeEvidenceAttestor.attest(extensionVersion);
        attempt.recordAttestedEvidence(evidence);
    }

    private void bindRuntime(ActivationAttempt attempt) {
        attempt.enter(ActivationPhase.RUNTIME_BINDING);

        AutoconfigureSession session = attempt.session();
        BootstrapResources resources = attempt.resources();
        resources.push(PlatformPropagationGate.shared().bindAcceptedPolicy(session.policy()), "propagation-gate");
        resources.push(PlatformLogControl.shared().bindAcceptedPolicy(session.policy()), "log-control");

        PlatformTracingMBeanRegistrar.RuntimeRegistration runtimeRegistration = session.jmxRegistrar().registerRuntimeControls(session.policy());
        attempt.recordRuntimeRegistration(runtimeRegistration.batchId());
        resources.push(runtimeRegistration, "runtime-jmx-controls");

        String batchId = attempt.batchId();
        ExtensionRuntimeEvidence evidence = attempt.evidence();
        resources.push(
                () -> terminalTransitions.publishTransportStale(session, batchId, evidence),
                "readiness-stale-transition"
        );
        resources.installShutdownHook(
                SHUTDOWN_HOOK_THREAD_NAME,
                cleanupFailure -> terminalTransitions.completeShutdown(session, batchId, evidence, cleanupFailure)
        );
    }

    private void publishReady(ActivationAttempt attempt) {
        attempt.enter(ActivationPhase.READINESS_PUBLICATION);

        AutoconfigureSession session = attempt.session();
        session.scrubbingRuntime().requireInstallationComplete();
        session.readiness().markInstalled(PlatformExtensionCapability.SANITIZER_INSTALLED);
        session.readiness().requireReady();

        ExtensionReadinessWireSnapshot ready = ExtensionReadinessWireSnapshot.ready(
                session.readiness(),
                session.sessionId().toString(),
                attempt.batchId(),
                INITIAL_EVIDENCE_GENERATION,
                attempt.evidence()
        );
        journal.publishReady(ready);
        lifecycle.commitActive(session);
        emitInstalled(ready);
    }

    private BootstrapException terminalize(ActivationAttempt attempt, Throwable failure) {
        return terminalTransitions.terminalizeActivationFailure(
                attempt.session(),
                attempt.resources(),
                failureCodeOf(attempt, failure),
                failure,
                attempt.batchId(),
                attempt.evidence()
        );
    }

    private static String failureCodeOf(ActivationAttempt attempt, Throwable failure) {
        return switch (failure) {
            case LinkageError ignored -> AttestationFailureCode.EXTENSION_LINKAGE_ERROR.name();
            case RuntimeEvidenceAttestationException attestation -> attestation.code().name();
            case RemoteJmxRegistrationDeniedException denied -> denied.failureCode().name();
            default -> attempt.phase().failureCode();
        };
    }

    private static void emitInstalled(ExtensionReadinessWireSnapshot ready) {
        log.info(
                AgentEventCodes.BOOT_INSTALLED,
                "Platform tracing extension installed",
                "lifecycle", ready.lifecycle(),
                "profile", PlatformExtensionReadiness.PROFILE,
                "protocolVersion", PlatformExtensionReadiness.PROTOCOL_VERSION,
                "extensionVersion", ready.evidence().extensionVersion()
        );
    }

    private enum ActivationPhase {
        DIAGNOSTIC_REGISTRATION(DIAGNOSTIC_MBEAN_REGISTRATION_FAILED),
        REMOTE_JMX_ASSESSMENT(RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN.name()),
        RUNTIME_EVIDENCE_ATTESTATION(RUNTIME_EVIDENCE_ATTESTATION_FAILED),
        RUNTIME_BINDING(ACTIVATION_FAILED),
        READINESS_PUBLICATION(ACTIVATION_FAILED);

        private final String failureCode;

        ActivationPhase(String failureCode) {
            this.failureCode = failureCode;
        }

        String failureCode() {
            return failureCode;
        }
    }

    private static final class ActivationAttempt {

        private final AutoconfigureSession session;
        private final BootstrapResources resources;

        private ActivationPhase phase = ActivationPhase.DIAGNOSTIC_REGISTRATION;
        private String batchId = "";
        private ExtensionRuntimeEvidence evidence;

        ActivationAttempt(AutoconfigureSession session, BootstrapResources resources) {
            this.session = Objects.requireNonNull(session, "session");
            this.resources = Objects.requireNonNull(resources, "resources");
            this.evidence = ExtensionRuntimeEvidence.unavailable(session.readiness().extensionVersion());
        }

        void enter(ActivationPhase next) {
            this.phase = Objects.requireNonNull(next, "next");
        }

        void recordAttestedEvidence(ExtensionRuntimeEvidence attested) {
            this.evidence = Objects.requireNonNull(attested, "attested");
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
