package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.ACTIVATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.PLATFORM_SAMPLER_REPLACED;

import java.util.Objects;
import java.util.Optional;
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
    private static final String PROLOGUE_SUBPHASE = "activation-prologue";
    private static final String NO_BATCH_ID = "";

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

    Optional<ActivationFailure> activate(SdkTracerProvider tracerProvider, Runnable installLifecycleShutdownHook) {
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
                failure = recordFailure(
                        PROLOGUE_SUBPHASE,
                        BootstrapFailureCodes.classify(cause, ACTIVATION_FAILED),
                        cause,
                        resources,
                        NO_BATCH_ID,
                        fallbackEvidence
                );
            }

            if (failure == null) {
                failure = runPhases(attempt);
            }
        }

        if (failure != null) {
            return Optional.of(failure);
        }

        emitInstalled(attempt.ready());
        return Optional.empty();
    }

    private ActivationFailure runPhases(ActivationAttempt attempt) {
        try {
            requireSessionSampler(attempt);
            assessRemoteJmx(attempt);
            bindRuntime(attempt);
            publishReady(attempt);
            return null;
        } catch (RuntimeException | Error cause) {
            return recordFailure(
                    attempt.phase().subphase(),
                    failureCodeOf(attempt, cause),
                    cause,
                    attempt.resources(),
                    attempt.batchId(),
                    attempt.evidence()
            );
        }
    }

    private ActivationFailure recordFailure(String subphase,
                                            String failureCode,
                                            Throwable cause,
                                            BootstrapResources resources,
                                            String batchId,
                                            ExtensionRuntimeEvidence evidence) {
        FailureSite site = FailureSite.activation(subphase);
        FailureAdmission admission = terminalTransitions.terminalize(site, failureCode, cause, batchId, evidence);
        return new ActivationFailure(site, failureCode, cause, admission, resources, batchId, evidence);
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

        throw new RemoteJmxRegistrationDeniedException(assessment);
    }

    private void bindRuntime(ActivationAttempt attempt) {
        attempt.enter(ActivationPhase.RUNTIME_BINDING);

        //noinspection resource -- сессия принадлежит попытке активации, закрывает её BootstrapResources
        AutoconfigureSession session = attempt.session();
        //noinspection resource -- runtime-регистрации принадлежат BootstrapResources, lifecycle hook — BootstrapCoordinator
        BootstrapResources resources = attempt.resources();
        //noinspection resource -- SdkTracerProvider принадлежит autoconfigure SDK и закрывается им, а не активацией
        session.scrubbingRuntime().bindAttributeValueLengthLimit(
                attempt.tracerProvider().getSpanLimits().getMaxAttributeValueLength()
        );
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
        lifecycle.commitActive();
        journal.publishReady(ready);
        attempt.recordReady(ready);
    }

    private static String failureCodeOf(ActivationAttempt attempt, Throwable failure) {
        return switch (failure) {
            case LinkageError ignored -> VerificationFailureCode.EXTENSION_LINKAGE_ERROR.name();
            case RemoteJmxRegistrationDeniedException denied -> denied.failureCode().name();
            case JmxRegistrationException registration when (registration.failureCode() != null) -> registration.failureCode();
            case BootstrapAdmissionRejectedException rejection -> rejection.primaryCode();
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

    record ActivationFailure(FailureSite site,
                             String failureCode,
                             Throwable cause,
                             FailureAdmission admission,
                             BootstrapResources resources,
                             String batchId,
                             ExtensionRuntimeEvidence evidence) {

        ActivationFailure {
            Objects.requireNonNull(site, "site");
            Objects.requireNonNull(failureCode, "failureCode");
            Objects.requireNonNull(cause, "cause");
            Objects.requireNonNull(admission, "admission");
            Objects.requireNonNull(batchId, "batchId");
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    private static final class ActivationAttempt {

        private final AutoconfigureSession session;
        private final BootstrapResources resources;
        private final SdkTracerProvider tracerProvider;
        private final ExtensionRuntimeEvidence evidence;

        private ActivationPhase phase = ActivationPhase.SAMPLER_POSTCONDITION;
        private String batchId = NO_BATCH_ID;
        private ExtensionReadinessWireSnapshot ready;

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

        void recordReady(ExtensionReadinessWireSnapshot published) {
            this.ready = Objects.requireNonNull(published, "published");
        }

        ExtensionReadinessWireSnapshot ready() {
            if (ready == null) {
                throw new IllegalStateException("Activation completed without READY publication");
            }

            return ready;
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
