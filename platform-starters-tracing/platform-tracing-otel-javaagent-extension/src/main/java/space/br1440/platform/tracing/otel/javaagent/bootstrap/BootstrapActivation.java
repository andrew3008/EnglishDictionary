package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.diagnostics.PlatformLogControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxDenialReason;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxExposureAssessment;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxMutationRegistrationGate;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.propagation.PlatformPropagationGate;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

final class BootstrapActivation {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(BootstrapActivation.class);

    private static final String DIAGNOSTIC_REGISTRATION_FAILED = "DIAGNOSTIC_MBEAN_REGISTRATION_FAILED";
    private static final String RUNTIME_EVIDENCE_ATTESTATION_FAILED = "RUNTIME_EVIDENCE_ATTESTATION_FAILED";
    private static final String ACTIVATION_FAILED = "ACTIVATION_FAILED";

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
        AutoconfigureSession activeSession = lifecycle.beginActivation();

        journal.publishInitializing(activeSession);

        BootstrapResources resources = new BootstrapResources(hookRegistry);
        lifecycle.attachResources(resources);

        String batchId = "";
        ExtensionRuntimeEvidence runtimeEvidence = ExtensionRuntimeEvidence.unavailable(activeSession.readiness().extensionVersion());
        String phaseFailureCode = DIAGNOSTIC_REGISTRATION_FAILED;
        try {
            diagnosticHandle.replace(
                    activeSession.jmxRegistrar().registerDiagnosticReadiness(journal::current)
            );

            phaseFailureCode = RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN.name();
            authorizeMutation(
                    activeSession,
                    resources,
                    batchId,
                    runtimeEvidence);

            phaseFailureCode = RUNTIME_EVIDENCE_ATTESTATION_FAILED;
            try {
                runtimeEvidence = runtimeEvidenceAttestor.attest(activeSession.readiness().extensionVersion());
            } catch (RuntimeEvidenceAttestationException failure) {
                throw terminalTransitions.terminalizeActivationFailure(
                        activeSession,
                        resources,
                        failure.code().name(),
                        failure,
                        batchId,
                        runtimeEvidence
                );
            }
            ExtensionRuntimeEvidence attestedEvidence = runtimeEvidence;

            phaseFailureCode = ACTIVATION_FAILED;
            resources.push(
                    PlatformPropagationGate.shared().bindAcceptedPolicy(activeSession.policy()),
                    "propagation-gate");
            resources.push(
                    PlatformLogControl.shared().bindAcceptedPolicy(activeSession.policy()),
                    "log-control");
            PlatformTracingMBeanRegistrar.RuntimeRegistration runtimeRegistration =
                    activeSession.jmxRegistrar().registerRuntimeControls(activeSession.policy());
            batchId = runtimeRegistration.batchId().toString();
            resources.push(runtimeRegistration, "runtime-jmx-controls");
            String registeredBatchId = batchId;
            resources.push(
                    () -> terminalTransitions.publishTransportStale(
                            activeSession,
                            registeredBatchId,
                            attestedEvidence),
                    "readiness-stale-transition");
            resources.installShutdownHook(
                    "platform-tracing-bootstrap-shutdown",
                    cleanupFailure -> terminalTransitions.completeShutdown(
                            activeSession,
                            registeredBatchId,
                            attestedEvidence,
                            cleanupFailure)
            );

            activeSession.scrubbingRuntime().requireInstallationComplete();
            activeSession.readiness().markInstalled(PlatformExtensionCapability.SANITIZER_INSTALLED);
            activeSession.readiness().requireReady();

            ExtensionReadinessWireSnapshot ready = ExtensionReadinessWireSnapshot.ready(
                    activeSession.readiness(),
                    activeSession.sessionId().toString(),
                    batchId,
                    1L,
                    attestedEvidence
            );
            journal.publishReady(ready);
            lifecycle.commitActive(activeSession);
            emitInstalled(ready);
        } catch (BootstrapException classified) {
            throw classified;
        } catch (RuntimeException | Error e) {
            String failureCode = e instanceof LinkageError
                    ? AttestationFailureCode.EXTENSION_LINKAGE_ERROR.name()
                    : phaseFailureCode;

            BootstrapException classified = terminalTransitions.terminalizeActivationFailure(
                    activeSession,
                    resources,
                    failureCode,
                    e,
                    batchId,
                    runtimeEvidence
            );
            PlatformThrowables.propagateIfFatal(e);
            throw classified;
        }
    }

    private void authorizeMutation(AutoconfigureSession activeSession,
                                   BootstrapResources resources,
                                   String batchId,
                                   ExtensionRuntimeEvidence runtimeEvidence) {
        RemoteJmxMutationRegistrationGate policy = activeSession.remoteJmxExposurePolicy();
        RemoteJmxExposureAssessment remoteJmxAssessment = policy.evaluateCurrentProcess();
        if (!remoteJmxAssessment.allowsLocalMutationRegistration()) {
            String failureCode = remoteJmxAssessment.failureCode().name();
            IllegalStateException cause = new IllegalStateException(
                    "Remote JMX security gate rejected local mutation registration: " + failureCode
            );

            throw terminalTransitions.terminalizeActivationFailure(
                    activeSession,
                    resources,
                    failureCode,
                    cause,
                    batchId,
                    runtimeEvidence
            );
        }

        policy.requireCurrentProcessPermit();
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
}
