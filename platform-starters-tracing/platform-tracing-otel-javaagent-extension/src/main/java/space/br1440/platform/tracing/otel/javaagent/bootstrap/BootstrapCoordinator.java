package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.diagnostics.PlatformLogControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.DomainMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxExposureAssessment;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxExposurePolicy;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxExposurePolicy.MutationRegistrationAuthorization;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxExposurePolicy.MutationRegistrationPermit;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.propagation.PlatformPropagationGate;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionLifecycle;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.SanitizerBootstrapException;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;

final class BootstrapCoordinator implements AutoCloseable {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(BootstrapCoordinator.class);
    private static final String TRANSPORT_STALE = "JMX_TRANSPORT_STALE";
    private static final String FAILED_ROLLBACK_INCOMPLETE = "FAILED_ROLLBACK_INCOMPLETE";

    private final Object lock = new Object();
    private final ShutdownHookRegistry hookRegistry;
    private final PlatformScrubberFactory scrubberFactory;
    private final RuntimeEvidenceAttestor runtimeEvidenceAttestor;
    private final Function<ConfigProperties, RemoteJmxExposurePolicy> remoteJmxExposurePolicyFactory;

    private final AtomicReference<BootstrapSnapshot> snapshot = new AtomicReference<>(
            BootstrapSnapshot.initializing()
    );

    private BootstrapState state = BootstrapState.NEW;
    private AutoconfigureSession session;
    private BootstrapFailure failure;
    private BootstrapResources resources;

    private AutoCloseable diagnosticHandle;

    static BootstrapCoordinator create() {
        return create(new PlatformScrubberFactory());
    }

    static BootstrapCoordinator create(PlatformScrubberFactory scrubberFactory) {
        return new BootstrapCoordinator(
                JvmShutdownHookRegistry.INSTANCE,
                scrubberFactory,
                ControlledAgentRuntimeAttestor.production()
        );
    }

    static BootstrapCoordinator create(PlatformScrubberFactory scrubberFactory,
                                       RuntimeEvidenceAttestor runtimeEvidenceAttestor) {
        return new BootstrapCoordinator(
                JvmShutdownHookRegistry.INSTANCE,
                scrubberFactory,
                runtimeEvidenceAttestor
        );
    }

    static BootstrapCoordinator create(
            PlatformScrubberFactory scrubberFactory,
            RuntimeEvidenceAttestor runtimeEvidenceAttestor,
            Function<ConfigProperties, RemoteJmxExposurePolicy> remoteJmxExposurePolicyFactory) {
        return new BootstrapCoordinator(
                JvmShutdownHookRegistry.INSTANCE,
                scrubberFactory,
                runtimeEvidenceAttestor,
                remoteJmxExposurePolicyFactory
        );
    }

    BootstrapCoordinator(ShutdownHookRegistry hookRegistry, PlatformScrubberFactory scrubberFactory) {
        this(hookRegistry, scrubberFactory, ControlledAgentRuntimeAttestor.production());
    }

    BootstrapCoordinator(ShutdownHookRegistry hookRegistry,
                         PlatformScrubberFactory scrubberFactory,
                         RuntimeEvidenceAttestor runtimeEvidenceAttestor) {
        this(
                hookRegistry,
                scrubberFactory,
                runtimeEvidenceAttestor,
                BootstrapCoordinator::currentProcessRemoteJmxPolicy);
    }

    BootstrapCoordinator(
            ShutdownHookRegistry hookRegistry,
            PlatformScrubberFactory scrubberFactory,
            RuntimeEvidenceAttestor runtimeEvidenceAttestor,
            Function<ConfigProperties, RemoteJmxExposurePolicy> remoteJmxExposurePolicyFactory) {
        this.hookRegistry = Objects.requireNonNull(hookRegistry, "hookRegistry");
        this.scrubberFactory = Objects.requireNonNull(scrubberFactory, "scrubberFactory");
        this.runtimeEvidenceAttestor = Objects.requireNonNull(runtimeEvidenceAttestor, "runtimeEvidenceAttestor");
        this.remoteJmxExposurePolicyFactory = Objects.requireNonNull(
                remoteJmxExposurePolicyFactory,
                "Remote JMX exposure policy factory must not be null");
    }

    AutoconfigureSession prepare(ConfigProperties config) {
        Objects.requireNonNull(config, "config");

        ConfigurationFingerprint fingerprint = ConfigurationFingerprint.of(config);

        synchronized (lock) {
            if (state == BootstrapState.FAILED
                    || state == BootstrapState.CLOSED
                    || state == BootstrapState.CLOSING) {
                throw stickyFailure("prepare");
            }

            if (state == BootstrapState.PREPARING) {
                throw fail(
                        "REENTRANT_OR_OUT_OF_ORDER",
                        "prepare",
                        new IllegalStateException("Bootstrap preparation is already in progress")
                );
            }

            if (state != BootstrapState.NEW) {
                boolean sameConfiguration = session != null && fingerprint.equals(session.fingerprint());
                String code = sameConfiguration ? "SECOND_SDK_BUILD" : "CONFIGURATION_SPLIT_BRAIN";
                throw fail(
                        code,
                        "prepare",
                        new IllegalStateException(
                                "Bootstrap coordinator already prepared/activated for this JVM; a second "
                                        + "AutoConfiguredOpenTelemetrySdk build is not supported"
                        )
                );
            }

            state = BootstrapState.PREPARING;
        }

        try {
            ExtensionConfig extensionConfig = new ExtensionConfig(config);
            if (!extensionConfig.scrubbing().enabled()) {
                String message = "%s=false is forbidden by the secure Agent profile"
                        .formatted(ExtensionPropertyNames.SCRUBBING_ENABLED);

                throw new ConfigurationException(message);
            }

            TracingSettingsSource policy = TracingSettingsSource.fromStartup(extensionConfig);
            ScrubbingRuntime scrubbingRuntime = scrubberFactory.buildRuntime(extensionConfig, config);
            RemoteJmxExposurePolicy remoteJmxExposurePolicy = Objects.requireNonNull(
                    remoteJmxExposurePolicyFactory.apply(config),
                    "Remote JMX exposure policy factory must not return null");
            AutoconfigureSession prepared = new AutoconfigureSession(
                    UUID.randomUUID(), fingerprint, extensionConfig, policy, scrubbingRuntime, remoteJmxExposurePolicy
            );
            prepared.readiness().markInstalled(PlatformExtensionCapability.CONFIGURATION_LOADED);

            synchronized (lock) {
                this.session = prepared;
                this.state = BootstrapState.PREPARED;
            }
            return prepared;
        } catch (SanitizerBootstrapException e) {
            throw fail(e.code(), "prepare", e);
        } catch (RuntimeException | Error e) {
            PlatformThrowables.propagateIfFatal(e);
            throw fail("CONFIGURATION_INITIALIZATION_FAILED", "prepare", e);
        }
    }

    private static RemoteJmxExposurePolicy currentProcessRemoteJmxPolicy(ConfigProperties ignored) {
        return RemoteJmxExposurePolicy.fromCurrentProcessStartupArguments();
    }

    AutoconfigureSession session() {
        synchronized (lock) {
            if (session == null) {
                throw stickyFailure("session");
            }
            return session;
        }
    }

    <T> T execute(String phaseFailureCode, Supplier<T> action) {
        synchronized (lock) {
            if (state == BootstrapState.FAILED
                    || state == BootstrapState.CLOSED
                    || state == BootstrapState.CLOSING) {
                throw stickyFailure(phaseFailureCode);
            }
        }

        try {
            return action.get();
        } catch (SanitizerBootstrapException e) {
            BootstrapException classified = fail(e.code(), phaseFailureCode, e);
            markSessionFailed(e.code(), e);
            throw classified;
        } catch (RuntimeException | Error e) {
            PlatformThrowables.propagateIfFatal(e);
            throw fail(phaseFailureCode, phaseFailureCode, e);
        }
    }

    private void markSessionFailed(String failureCode, Throwable cause) {
        AutoconfigureSession failedSession;
        synchronized (lock) {
            failedSession = session;
        }

        if (failedSession != null) {
            publishFailedSnapshot(failedSession, failureCode, cause);
        }
    }

    void activate() {
        AutoconfigureSession activeSession;
        synchronized (lock) {
            if (state == BootstrapState.NEW) {
                throw fail("PLATFORM_ACP_NOT_EXECUTED", "activate",
                        new IllegalStateException(
                                "AgentListener.afterAgent invoked without a prior ACP prepare() call "
                                + "(declarative configuration bypass or missing platform ACP registration)"
                        )
                );
            }

            if (state == BootstrapState.PREPARING) {
                throw fail("REENTRANT_OR_OUT_OF_ORDER", "activate",
                        new IllegalStateException("afterAgent invoked while bootstrap preparation is still in progress"));
            }

            if (state == BootstrapState.ACTIVATING) {
                throw fail("DUPLICATE_ACTIVATION", "activate",
                        new IllegalStateException("afterAgent invoked more than once for this coordinator")
                );
            }

            if (state == BootstrapState.ACTIVE) {
                throw rejectWithoutStateChange(
                        "DUPLICATE_ACTIVATION",
                        "activate",
                        new IllegalStateException("afterAgent invoked more than once for this coordinator")
                );
            }

            if (state == BootstrapState.FAILED
                    || state == BootstrapState.CLOSED
                    || state == BootstrapState.CLOSING) {
                throw stickyFailure("activate");
            }

            state = BootstrapState.ACTIVATING;
            activeSession = session;
        }

        BootstrapSnapshot initializingSnapshot = BootstrapSnapshot.initializing(
                activeSession,
                ExtensionReadinessWireSnapshot.initializing(
                        activeSession.readiness(),
                        activeSession.sessionId().toString()
                )
        );
        snapshot.set(initializingSnapshot);

        BootstrapResources resources = new BootstrapResources(hookRegistry);
        synchronized (lock) {
            this.resources = resources;
        }
        String batchId = "";
        ExtensionRuntimeEvidence runtimeEvidence =
                ExtensionRuntimeEvidence.unavailable(activeSession.readiness().extensionVersion());
        String phaseFailureCode = "DIAGNOSTIC_MBEAN_REGISTRATION_FAILED";
        try {
            diagnosticHandle = activeSession.jmxRegistrar().registerDiagnosticReadiness(() -> snapshot.get().wire());

            phaseFailureCode = RemoteJmxExposurePolicy.FAILURE_EVIDENCE_UNKNOWN;
            MutationRegistrationAuthorization mutationAuthorization = activeSession.remoteJmxExposurePolicy()
                    .authorizeCurrentProcess();
            RemoteJmxExposureAssessment remoteJmxAssessment = mutationAuthorization.assessment();
            if (!mutationAuthorization.authorized()) {
                String failureCode = remoteJmxAssessment.failureCode();
                IllegalStateException cause = new IllegalStateException(
                        "Remote JMX security gate rejected local mutation registration: " + failureCode
                );
                throw terminalizeActivationFailure(
                        activeSession,
                        resources,
                        failureCode,
                        cause,
                        batchId,
                        runtimeEvidence
                );
            }
            MutationRegistrationPermit mutationRegistrationPermit = mutationAuthorization.requirePermit();

            phaseFailureCode = "RUNTIME_EVIDENCE_ATTESTATION_FAILED";
            try {
                runtimeEvidence = runtimeEvidenceAttestor.attest(activeSession.readiness().extensionVersion());
            } catch (RuntimeEvidenceAttestationException failure) {
                throw terminalizeActivationFailure(
                        activeSession,
                        resources,
                        failure.code(),
                        failure,
                        batchId,
                        runtimeEvidence
                );
            }
            ExtensionRuntimeEvidence attestedEvidence = runtimeEvidence;

            phaseFailureCode = "ACTIVATION_FAILED";
            resources.push(
                    PlatformPropagationGate.shared().bindAcceptedPolicy(activeSession.policy()),
                    "propagation-gate");
            resources.push(
                    PlatformLogControl.shared().bindAcceptedPolicy(activeSession.policy()),
                    "log-control");
            DomainMBeanRegistrar.RuntimeRegistration runtimeRegistration =
                    activeSession.jmxRegistrar().registerRuntimeControls(
                            activeSession.policy(),
                            mutationRegistrationPermit);
            batchId = runtimeRegistration.batchId().toString();
            resources.push(runtimeRegistration, "runtime-jmx-controls");
            String registeredBatchId = batchId;
            ReadinessShutdownTransition shutdownTransition = new ReadinessShutdownTransition(
                    () -> publishTransportStale(activeSession, registeredBatchId, attestedEvidence)
            );
            resources.push(shutdownTransition, "readiness-stale-transition");
            resources.installShutdownHook(
                    "platform-tracing-bootstrap-shutdown",
                    cleanupFailure -> completeShutdown(
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
            commitReady(
                    activeSession,
                    initializingSnapshot,
                    BootstrapSnapshot.of(activeSession, BootstrapState.ACTIVE, null, null, ready),
                    shutdownTransition
            );
        } catch (BootstrapException classified) {
            throw classified;
        } catch (RuntimeException | Error e) {
            String failureCode = e instanceof LinkageError
                    ? ControlledAgentRuntimeAttestor.EXTENSION_LINKAGE_ERROR
                    : phaseFailureCode;
            BootstrapException classified = terminalizeActivationFailure(
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

    BootstrapSnapshot currentSnapshot() {
        return snapshot.get();
    }

    @Override
    public void close() {
        BootstrapResources resourcesToClose;
        synchronized (lock) {
            if (state == BootstrapState.CLOSED || state == BootstrapState.CLOSING) {
                return;
            }
            state = BootstrapState.CLOSING;
            resourcesToClose = resources;
            resources = null;
        }

        Throwable cleanupFailure = null;
        if (resourcesToClose != null) {
            try {
                resourcesToClose.close();
            } catch (RuntimeException | Error failure) {
                cleanupFailure = failure;
            }
        }
        try {
            closeDiagnosticHandle();
        } catch (RuntimeException | Error failure) {
            cleanupFailure = aggregateCleanupFailure(cleanupFailure, failure);
        }

        if (cleanupFailure != null) {
            AutoconfigureSession activeSession;
            synchronized (lock) {
                this.failure = new BootstrapFailure(FAILED_ROLLBACK_INCOMPLETE, "close", cleanupFailure);
                this.state = BootstrapState.FAILED;
                activeSession = session;
            }
            if (activeSession != null) {
                ExtensionReadinessWireSnapshot currentWire = snapshot.get().wire();
                activeSession.readiness().failTerminalCleanup(FAILED_ROLLBACK_INCOMPLETE, cleanupFailure);
                publishFailedSnapshot(
                        activeSession,
                        FAILED_ROLLBACK_INCOMPLETE,
                        cleanupFailure,
                        currentWire.batchId(),
                        currentWire.evidence()
                );
            }
            rethrowCleanupFailure(cleanupFailure);
        }

        synchronized (lock) {
            state = BootstrapState.CLOSED;
        }
    }

    private void publishFailedSnapshot(AutoconfigureSession activeSession, String failureCode, Throwable cause) {
        ExtensionReadinessWireSnapshot currentWire = snapshot.get().wire();
        ExtensionRuntimeEvidence evidence = currentWire.evidence();
        String batchId = currentWire.batchId();
        publishFailedSnapshot(activeSession, failureCode, cause, batchId, evidence);
    }

    private void publishFailedSnapshot(AutoconfigureSession activeSession,
                                       String failureCode,
                                       Throwable cause,
                                       String batchId,
                                       ExtensionRuntimeEvidence evidence) {
        synchronized (lock) {
            activeSession.readiness().fail(failureCode, cause);
            String runtimeEpoch = activeSession.sessionId().toString();
            BootstrapSnapshot current = snapshot.get();
            String effectiveFailureCode = activeSession.readiness().failureCode();
            if (PlatformExtensionLifecycle.FAILED.name().equals(current.wire().lifecycle())
                    && runtimeEpoch.equals(current.wire().runtimeEpoch())
                    && effectiveFailureCode.equals(current.wire().failureCode())) {
                return;
            }

            long evidenceGeneration = runtimeEpoch.equals(current.wire().runtimeEpoch())
                    ? current.wire().evidenceGeneration() + 1L
                    : 1L;
            ExtensionReadinessWireSnapshot failed = ExtensionReadinessWireSnapshot.failed(
                    activeSession.readiness(),
                    runtimeEpoch,
                    batchId,
                    evidenceGeneration,
                    effectiveFailureCode,
                    evidence
            );
            snapshot.set(BootstrapSnapshot.of(
                    activeSession,
                    BootstrapState.FAILED,
                    effectiveFailureCode,
                    activeSession.readiness().failureMessage(),
                    failed
            ));
        }
    }

    private void commitReady(AutoconfigureSession activeSession,
                             BootstrapSnapshot expected,
                             BootstrapSnapshot ready,
                             ReadinessShutdownTransition shutdownTransition) {
        synchronized (lock) {
            if (state != BootstrapState.ACTIVATING) {
                throw new IllegalStateException("Bootstrap is no longer ACTIVATING during READY commit");
            }
            if (!shutdownTransition.arm()) {
                throw new IllegalStateException("Readiness shutdown transition closed before READY commit");
            }
            if (!snapshot.compareAndSet(expected, ready)) {
                throw new IllegalStateException("Readiness snapshot changed before READY commit");
            }
            this.session = activeSession;
            this.state = BootstrapState.ACTIVE;
        }
    }

    private void publishTransportStale(AutoconfigureSession activeSession,
                                       String batchId,
                                       ExtensionRuntimeEvidence evidence) {
        IllegalStateException cause = new IllegalStateException(
                "JMX runtime controls are being deregistered"
        );
        synchronized (lock) {
            if (state != BootstrapState.ACTIVE) {
                return;
            }
            this.failure = new BootstrapFailure(TRANSPORT_STALE, "shutdown", cause);
            this.state = BootstrapState.FAILED;
            publishFailedSnapshot(activeSession, TRANSPORT_STALE, cause, batchId, evidence);
        }
    }

    private void completeShutdown(AutoconfigureSession activeSession,
                                  String batchId,
                                  ExtensionRuntimeEvidence evidence,
                                  Throwable cleanupFailure) {
        Throwable terminalFailure = cleanupFailure;
        Throwable diagnosticCleanupFailure = null;
        try {
            closeDiagnosticHandle();
        } catch (RuntimeException | Error failure) {
            diagnosticCleanupFailure = failure;
            terminalFailure = aggregateCleanupFailure(terminalFailure, failure);
        }

        if (terminalFailure == null) {
            return;
        }

        synchronized (lock) {
            this.failure = new BootstrapFailure(FAILED_ROLLBACK_INCOMPLETE, "shutdown", terminalFailure);
            this.state = BootstrapState.FAILED;
        }
        activeSession.readiness().failTerminalCleanup(FAILED_ROLLBACK_INCOMPLETE, terminalFailure);
        publishFailedSnapshot(
                activeSession,
                FAILED_ROLLBACK_INCOMPLETE,
                terminalFailure,
                batchId,
                evidence
        );
        log.warn(
                AgentEventCodes.BOOT_CLASSIFIED_FAILURE,
                "Platform bootstrap shutdown cleanup failed",
                terminalFailure,
                "phase",
                "shutdown"
        );

        if (diagnosticCleanupFailure != null) {
            rethrowCleanupFailure(terminalFailure);
        }
    }

    private BootstrapException terminalizeActivationFailure(AutoconfigureSession activeSession,
                                                             BootstrapResources resources,
                                                             String failureCode,
                                                             Throwable cause,
                                                             String batchId,
                                                             ExtensionRuntimeEvidence evidence) {
        Throwable cleanupFailure = rollback(resources, cause);
        boolean rollbackIncomplete = cleanupFailure != null
                || FAILED_ROLLBACK_INCOMPLETE.equals(activeSession.readiness().failureCode());
        String effectiveFailureCode = rollbackIncomplete ? FAILED_ROLLBACK_INCOMPLETE : failureCode;
        BootstrapException classified = fail(effectiveFailureCode, "activate", cause);
        if (rollbackIncomplete) {
            activeSession.readiness().failTerminalCleanup(FAILED_ROLLBACK_INCOMPLETE, cause);
        }
        publishFailedSnapshot(activeSession, effectiveFailureCode, cause, batchId, evidence);
        PlatformThrowables.propagateIfFatal(cause);
        PlatformThrowables.propagateIfFatal(cleanupFailure);
        return classified;
    }

    private static Throwable rollback(BootstrapResources resources, Throwable primary) {
        try {
            resources.close();
            return null;
        } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != primary) {
                primary.addSuppressed(cleanupFailure);
            }
            return cleanupFailure;
        }
    }

    private static Throwable aggregateCleanupFailure(Throwable aggregate, Throwable failure) {
        if (aggregate == null) {
            return failure;
        }
        if (aggregate == failure) {
            return aggregate;
        }
        if (failure instanceof Error && !(aggregate instanceof Error)) {
            failure.addSuppressed(aggregate);
            return failure;
        }

        aggregate.addSuppressed(failure);
        return aggregate;
    }

    private static void rethrowCleanupFailure(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        throw (RuntimeException) failure;
    }

    void closeDiagnosticHandle() {
        AutoCloseable handle;
        synchronized (lock) {
            handle = diagnosticHandle;
            diagnosticHandle = null;
        }
        if (handle == null) {
            return;
        }

        try {
            handle.close();
        } catch (Exception failure) {
            throw new IllegalStateException("Diagnostic readiness MBean cleanup failed", failure);
        }
    }

    private BootstrapException fail(String code, String phase, Throwable cause) {
        synchronized (lock) {
            if (state != BootstrapState.FAILED) {
                this.failure = new BootstrapFailure(code, phase, cause);
                this.state = BootstrapState.FAILED;
            }

            log.warn(AgentEventCodes.BOOT_CLASSIFIED_FAILURE, "Bootstrap phase failed", cause, "phase", phase);

            return new BootstrapException(failure);
        }
    }

    private BootstrapException rejectWithoutStateChange(String code, String phase, Throwable cause) {
        synchronized (lock) {
            BootstrapFailure rejection = new BootstrapFailure(code, phase, cause);
            log.warn(AgentEventCodes.BOOT_CLASSIFIED_FAILURE, "Bootstrap operation rejected", cause, "phase", phase);
            return new BootstrapException(rejection);
        }
    }

    private BootstrapException stickyFailure(String phase) {
        synchronized (lock) {
            if (failure == null) {
                BootstrapFailure cause = new BootstrapFailure(
                        "BOOTSTRAP_TERMINAL",
                        phase,
                        new IllegalStateException("Bootstrap coordinator is in terminal state " + state)
                );
                return new BootstrapException(cause);
            }

            BootstrapFailure cause = new BootstrapFailure(failure.code(), phase, failure.cause());
            return new BootstrapException(cause);
        }
    }
}
