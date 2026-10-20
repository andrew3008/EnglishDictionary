package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.ACTIVATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.PLATFORM_SAMPLER_REPLACED;

import java.util.Objects;
import java.util.UUID;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapResources;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.ShutdownHookRegistry;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.verification.VerificationFailureCode;
import space.br1440.platform.tracing.otel.javaagent.diagnostics.PlatformLogControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxDenialReason;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxRegistrationAssessment;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.BootstrapLogEvent;
import space.br1440.platform.tracing.otel.javaagent.propagation.PlatformPropagationGate;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionLifecycle;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadinessSnapshot;

import io.opentelemetry.sdk.trace.SdkTracerProvider;

final class BootstrapActivation {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(BootstrapActivation.class);

    private static final String INSTALLATION_PROFILE = "platform-agent-secure-v1";
    private static final int INSTALLATION_PROTOCOL_VERSION = 1;
    private static final String SHUTDOWN_HOOK_THREAD_NAME = "platform-tracing-bootstrap-shutdown";
    private static final long INITIAL_EVIDENCE_GENERATION = 1L;

    private final BootstrapLifecycleState lifecycle;
    private final BootstrapReadinessJournal journal;
    private final BootstrapTerminalTransitions terminalTransitions;
    private final ShutdownHookRegistry hookRegistry;

    BootstrapActivation(BootstrapLifecycleState lifecycle,
                        BootstrapReadinessJournal journal,
                        BootstrapTerminalTransitions terminalTransitions,
                        ShutdownHookRegistry hookRegistry) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.terminalTransitions = Objects.requireNonNull(terminalTransitions, "terminalTransitions");
        this.hookRegistry = Objects.requireNonNull(hookRegistry, "hookRegistry");
    }

    void activate(SdkTracerProvider tracerProvider) {
        synchronized (lifecycle.monitor()) {
            activateExclusively(Objects.requireNonNull(tracerProvider, "tracerProvider"));
        }
    }

    private void activateExclusively(SdkTracerProvider tracerProvider) {
        AutoconfigureSession session = lifecycle.beginActivation();

        BootstrapResources resources = new BootstrapResources(hookRegistry);
        lifecycle.attachResources(resources);

        ActivationAttempt attempt = new ActivationAttempt(session, resources, tracerProvider);
        try {
            requireSessionSampler(attempt);
            assessRemoteJmx(attempt);
            bindRuntime(attempt);
            publishReady(attempt);
        } catch (RuntimeException | Error failure) {
            throw terminalize(attempt, failure);
        }
    }

    private static void requireSessionSampler(ActivationAttempt attempt) {
        attempt.enter(ActivationPhase.SAMPLER_POSTCONDITION);

        AutoconfigureSession session = attempt.session();
        if (attempt.tracerProvider().getSampler() != session.sampler()) {
            throw new IllegalStateException("Installed SDK sampler is not the platform session sampler");
        }
        session.readiness().markInstalled(PlatformExtensionCapability.PLATFORM_SAMPLER_INSTALLED);
    }

    private void assessRemoteJmx(ActivationAttempt attempt) {
        attempt.enter(ActivationPhase.REMOTE_JMX_ASSESSMENT);

        RemoteJmxRegistrationAssessment assessment = attempt.session().remoteJmxMutationRegistrationAssessor().assess();
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

        AutoconfigureSession session = attempt.session();
        //noinspection resource -- владение передаётся жизненному циклу BootstrapResources, закрытие происходит через shutdown hook / завершение attempt
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
        if (!attempt.evidence().complete()) {
            throw new IllegalStateException("Runtime attestation evidence is incomplete");
        }
        session.scrubbingRuntime().requireInstallationComplete();
        session.readiness().markInstalled(PlatformExtensionCapability.SANITIZER_INSTALLED);
        PlatformExtensionReadinessSnapshot readiness = session.readiness().completeInitialization();

        ExtensionReadinessWireSnapshot ready = ExtensionReadinessWireSnapshot.ready(
                readiness,
                session.agentInstanceId().toString(),
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
                attempt.phase().subphase(),
                failure,
                attempt.batchId(),
                attempt.evidence()
        );
    }

    private static String failureCodeOf(ActivationAttempt attempt, Throwable failure) {
        return switch (failure) {
            case LinkageError ignored -> VerificationFailureCode.EXTENSION_LINKAGE_ERROR.name();
            case RemoteJmxRegistrationDeniedException denied -> denied.failureCode().name();
            default -> attempt.phase().failureCode();
        };
    }

    private static void emitInstalled(ExtensionReadinessWireSnapshot ready) {
        log.log(BootstrapLogEvent.EXTENSION_INSTALLED, fields -> fields
                .putEnum(AgentLogField.LIFECYCLE, PlatformExtensionLifecycle.valueOf(ready.lifecycle()))
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

    private static final class ActivationAttempt {

        private final AutoconfigureSession session;
        private final BootstrapResources resources;
        private final SdkTracerProvider tracerProvider;

        private ActivationPhase phase = ActivationPhase.SAMPLER_POSTCONDITION;
        private String batchId = "";
        private final ExtensionRuntimeEvidence evidence;

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
