package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.AGENT_STARTUP_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.CONFIGURATION_INITIALIZATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.DIAGNOSTIC_MBEAN_REGISTRATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.PLATFORM_ACP_NOT_EXECUTED;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapCleanupFailures;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapResources;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.JvmShutdownHookRegistry;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.ShutdownHookRegistry;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSessionFactory;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.verification.ExtensionRuntimeVerifier;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.verification.VerificationException;
import space.br1440.platform.tracing.otel.javaagent.configuration.ConfigurationPropertyException;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.JmxRegistrationException;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxMutationRegistrationAssessor;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.SanitizerBootstrapException;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

final class BootstrapCoordinator implements AutoCloseable {

    private static final String SHUTDOWN_HOOK_THREAD_NAME = "platform-tracing-bootstrap-shutdown";

    private final BootstrapLifecycleState lifecycle;
    private final BootstrapReadinessJournal journal;
    private final DiagnosticReadinessHandle diagnosticHandle;
    private final ShutdownHookRegistry hookRegistry;
    private final PlatformTracingMBeanRegistrar jmxRegistrar;
    private final PlatformExtensionReadiness readiness;
    private final AgentInstanceId agentInstanceId;
    private final BootstrapTerminalTransitions terminalTransitions;
    private final AutoconfigureSessionFactory sessionFactory;
    private final ExtensionRuntimeVerifier runtimeVerifier;
    private final BootstrapActivation activation;
    private final Object preparationOwnershipMonitor = new Object();

    private boolean lifecycleShutdownHookInstalled;
    private Thread preparationOwner;

    static BootstrapCoordinator createDefault() {
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        return new BootstrapCoordinator(
                JvmShutdownHookRegistry.INSTANCE,
                new PlatformScrubberFactory(),
                ExtensionRuntimeVerifier.forCurrentJvm(),
                AutoconfigureSessionFactory::currentProcessRemoteJmxAssessor,
                new PlatformTracingMBeanRegistrar(agentInstanceId),
                new DiagnosticReadinessHandle()
        );
    }

    BootstrapCoordinator(ShutdownHookRegistry hookRegistry,
                         PlatformScrubberFactory scrubberFactory,
                         ExtensionRuntimeVerifier runtimeVerifier,
                         Function<ConfigProperties, RemoteJmxMutationRegistrationAssessor> remoteJmxAssessorFactory,
                         PlatformTracingMBeanRegistrar jmxRegistrar,
                         DiagnosticReadinessHandle diagnosticHandle) {
        this.lifecycle = new BootstrapLifecycleState();
        this.diagnosticHandle = Objects.requireNonNull(diagnosticHandle, "diagnosticHandle");
        this.hookRegistry = Objects.requireNonNull(hookRegistry, "hookRegistry");
        this.jmxRegistrar = Objects.requireNonNull(jmxRegistrar, "jmxRegistrar");
        this.readiness = jmxRegistrar.extensionReadiness();
        this.agentInstanceId = jmxRegistrar.agentInstanceId();
        this.journal = new BootstrapReadinessJournal(lifecycle.monitor(), readiness, agentInstanceId);
        this.terminalTransitions = new BootstrapTerminalTransitions(lifecycle, journal);
        this.sessionFactory = new AutoconfigureSessionFactory(scrubberFactory, remoteJmxAssessorFactory);
        this.runtimeVerifier = Objects.requireNonNull(runtimeVerifier, "runtimeVerifier");
        this.activation = new BootstrapActivation(
                lifecycle,
                journal,
                terminalTransitions
        );
    }

    AutoconfigureSession prepare(ConfigProperties config) {
        Objects.requireNonNull(config, "config");

        try {
            acquirePreparationOwnership();
        } catch (BootstrapAdmissionRejectedException rejection) {
            logAdmissionRejection(rejection);
            throw rejection;
        }

        try {
            return prepareAsOwner(config);
        } finally {
            releasePreparationOwnership();
        }
    }

    /**
     * Любой выход из допущенного prepare завершается {@code PREPARED} или settlement в {@code FAILED};
     * локально владеемая prepared session закрывается settlement.
     */
    private AutoconfigureSession prepareAsOwner(ConfigProperties config) {
        try {
            journal.publishInitializing();
        } catch (RuntimeException | Error publicationFailure) {
            throw new BootstrapException(handleFailure(
                    FailureSite.prepareReadinessPublication(),
                    BootstrapFailureCodes.classify(publicationFailure, CONFIGURATION_INITIALIZATION_FAILED),
                    publicationFailure,
                    null,
                    journal.current().evidence()
            ));
        }

        AutoconfigureSession prepared = createPreparedSession(config);
        try {
            registerDiagnosticReadiness();
        } catch (RuntimeException | Error registrationFailure) {
            throw new BootstrapException(handleFailure(
                    FailureSite.prepareDiagnosticRegistration(),
                    DIAGNOSTIC_MBEAN_REGISTRATION_FAILED,
                    registrationFailure,
                    prepared,
                    prepared.evidence()
            ));
        }

        try {
            lifecycle.completePrepare(prepared);
        } catch (RuntimeException | Error completionFailure) {
            throw new BootstrapException(handleFailure(
                    FailureSite.prepareConfiguration(),
                    BootstrapFailureCodes.classify(completionFailure, CONFIGURATION_INITIALIZATION_FAILED),
                    completionFailure,
                    prepared,
                    prepared.evidence()
            ));
        }

        return prepared;
    }

    private AutoconfigureSession createPreparedSession(ConfigProperties config) {
        try {
            String extensionVersion = readiness.snapshot().extensionVersion();
            ExtensionRuntimeEvidence evidence = runtimeVerifier.verify(extensionVersion);
            return sessionFactory.create(
                    config,
                    jmxRegistrar,
                    agentInstanceId,
                    evidence
            );
        } catch (RuntimeException | Error failure) {
            throw new BootstrapException(handlePreparationFailure(failure));
        }
    }

    private BootstrapFailure handlePreparationFailure(Throwable failure) {
        ExtensionRuntimeEvidence evidence = unavailableEvidence();
        return switch (failure) {
            case VerificationException verification -> handleFailure(
                    FailureSite.prepareConfiguration(),
                    verification.code().name(),
                    verification,
                    null,
                    evidence
            );

            case SanitizerBootstrapException sanitizer when sanitizer.propertyName() != null -> handleFailure(
                    FailureSite.prepareValidation(sanitizer.propertyName()),
                    sanitizer.code(),
                    sanitizer,
                    null,
                    evidence
            );

            case ConfigurationPropertyException property -> handleFailure(
                    FailureSite.prepareValidation(property.propertyName()),
                    CONFIGURATION_INITIALIZATION_FAILED,
                    property.failure(),
                    null,
                    evidence
            );

            default -> handleFailure(
                    FailureSite.prepareConfiguration(),
                    BootstrapFailureCodes.classify(failure, CONFIGURATION_INITIALIZATION_FAILED),
                    failure,
                    null,
                    evidence
            );
        };
    }

    AutoconfigureSession session() {
        return lifecycle.requireSession();
    }

    <T> T execute(CustomizationStep step, Supplier<T> action) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(action, "action");

        FailureSite site = FailureSite.customization(step);
        lifecycle.requireOperable(site.phase());

        try {
            return action.get();
        } catch (RuntimeException | Error failure) {
            throw new BootstrapException(handleFailure(
                    site,
                    BootstrapFailureCodes.classify(failure, step.failureCode()),
                    failure,
                    null,
                    currentEvidence()
            ));
        }
    }

    void activate(SdkTracerProvider tracerProvider) {
        Objects.requireNonNull(tracerProvider, "tracerProvider");

        ExtensionRuntimeEvidence unavailableEvidence = unavailableEvidence();
        Optional<FailureAdmission> missingPreparation = terminalTransitions.recordMissingPreparation(unavailableEvidence);
        if (missingPreparation.isPresent()) {
            FailureAdmission admission = missingPreparation.get();
            throw new BootstrapException(completeFailure(new FailureContext(
                    FailureSite.activation("activate"),
                    PLATFORM_ACP_NOT_EXECUTED,
                    admission.failure().cause(),
                    admission,
                    null,
                    null,
                    BatchIds.NONE,
                    unavailableEvidence
            )));
        }

        Optional<FailureContext> activationFailure;
        try {
            activationFailure = activation.activate(tracerProvider, this::installLifecycleShutdownHook);
        } catch (BootstrapAdmissionRejectedException rejection) {
            logAdmissionRejection(rejection);
            throw rejection;
        }

        if (activationFailure.isPresent()) {
            FailureContext failure = activationFailure.get();
            requireLifecycleMonitorNotHeld(failure.cause());
            throw new BootstrapException(completeFailure(failure));
        }
    }

    void onStartupFailure(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        requireLifecycleMonitorNotHeld(cause);

        ExtensionRuntimeEvidence evidence = currentEvidence();
        Optional<FailureAdmission> admission = terminalTransitions.terminalizeStartup(cause, evidence);
        if (admission.isEmpty()) {
            return;
        }

        completeFailure(new FailureContext(
                FailureSite.startup(),
                AGENT_STARTUP_FAILED,
                cause,
                admission.get(),
                null,
                null,
                BatchIds.NONE,
                evidence
        ));
    }

    ExtensionReadinessWireSnapshot currentWire() {
        return journal.current();
    }

    BootstrapState state() {
        return lifecycle.state();
    }

    BootstrapDiagnosticSnapshot diagnosticSnapshot() {
        return lifecycle.diagnosticSnapshot();
    }

    @Override
    public void close() {
        BootstrapLifecycleState.CloseAcquisition acquisition = acquireCloseAfterPreparation();
        if (acquisition.reentrant()) {
            return;
        }

        if (!acquisition.owner()) {
            rethrowIfPresent(acquisition.awaitOutcome());
            return;
        }

        Throwable outcome = null;
        try {
            outcome = closeAsOwner(acquisition.context());
        } catch (RuntimeException | Error unexpectedFailure) {
            outcome = unexpectedFailure;
        } finally {
            lifecycle.completeClose(acquisition.flight(), outcome);
        }

        rethrowIfPresent(outcome);
    }

    private void acquirePreparationOwnership() {
        synchronized (preparationOwnershipMonitor) {
            lifecycle.beginPrepare();
            preparationOwner = Thread.currentThread();
        }
    }

    private void releasePreparationOwnership() {
        synchronized (preparationOwnershipMonitor) {
            if (preparationOwner != Thread.currentThread()) {
                throw new IllegalMonitorStateException(
                        "Bootstrap preparation ownership can only be released by its owner"
                );
            }

            preparationOwner = null;
            preparationOwnershipMonitor.notifyAll();
        }
    }

    private BootstrapLifecycleState.CloseAcquisition acquireCloseAfterPreparation() {
        boolean interrupted = false;
        try {
            synchronized (preparationOwnershipMonitor) {
                if (preparationOwner == Thread.currentThread()) {
                    throw new IllegalStateException(
                            "Bootstrap close cannot run reentrantly during preparation"
                    );
                }

                while (preparationOwner != null) {
                    try {
                        preparationOwnershipMonitor.wait();
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }

                return lifecycle.acquireClose();
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Throwable closeAsOwner(BootstrapLifecycleState.CloseContext context) {
        Throwable cleanupFailure = null;
        Throwable priorPublicationFailure = null;
        boolean failureSnapshotPublished = false;

        try {
            diagnosticHandle.close();
        } catch (RuntimeException | Error withdrawalFailure) {
            cleanupFailure = BootstrapCleanupFailures.withResourceLabel(
                    "diagnostic-readiness",
                    withdrawalFailure
            );
            try {
                terminalTransitions.publishCloseFailureSnapshot(context, cleanupFailure);
                failureSnapshotPublished = true;
            } catch (RuntimeException | Error publicationFailure) {
                priorPublicationFailure = publicationFailure;
            }
        }

        if (cleanupFailure != null && !failureSnapshotPublished) {
            // Без снятого READY и без FAILED-fence teardown оставил бы READY над остановленным runtime;
            // ресурсы удерживает CloseContext завершённого close-flight до выхода JVM.
            return terminalTransitions.completeCloseFailure(
                    context,
                    cleanupFailure,
                    priorPublicationFailure,
                    false
            );
        }

        BootstrapResources resources = context.resources();
        Throwable runtimeCleanupFailure = resources == null
                ? closePreparedSession(context.session())
                : closeActivationResources(resources);
        if (runtimeCleanupFailure != null) {
            cleanupFailure = BootstrapCleanupFailures.aggregate(cleanupFailure, runtimeCleanupFailure);
        }

        if (cleanupFailure == null) {
            lifecycle.markClosed();
            return null;
        }

        return terminalTransitions.completeCloseFailure(
                context,
                cleanupFailure,
                priorPublicationFailure,
                failureSnapshotPublished
        );
    }

    private void installLifecycleShutdownHook() {
        if (!Thread.holdsLock(lifecycle.monitor())) {
            throw new IllegalMonitorStateException(
                    "Lifecycle shutdown hook installation requires the bootstrap lifecycle monitor"
            );
        }

        if (lifecycleShutdownHookInstalled) {
            return;
        }

        hookRegistry.register(SHUTDOWN_HOOK_THREAD_NAME, this::closeFromShutdownHook);
        lifecycleShutdownHookInstalled = true;
    }

    private void closeFromShutdownHook() {
        try {
            close();
        } catch (RuntimeException | Error ignored) {
        }
    }

    private BootstrapFailure handleFailure(FailureSite site,
                                           String failureCode,
                                           Throwable cause,
                                           AutoconfigureSession locallyOwned,
                                           ExtensionRuntimeEvidence evidence) {
        requireLifecycleMonitorNotHeld(cause);
        FailureAdmission admission = terminalTransitions.terminalize(site, failureCode, cause, BatchIds.NONE, evidence);
        return completeFailure(new FailureContext(
                site,
                failureCode,
                cause,
                admission,
                locallyOwned,
                null,
                BatchIds.NONE,
                evidence
        ));
    }

    private BootstrapFailure completeFailure(FailureContext context) {
        FailureAdmission admission = context.admission();
        Throwable cause = context.cause();
        SecondaryFailures secondary = new SecondaryFailures(admission.failure().cause(), cause);
        secondary.noteFatal(admission.projectionFailure());

        Throwable resourcesCleanupFailure = closeActivationResources(context.resources());
        //noinspection resource -- session принадлежит settlement: её закрывает closePreparedSession
        Throwable sessionCleanupFailure = closePreparedSession(
                context.ownedSession() != null ? context.ownedSession() : admission.claimedSession()
        );

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
                registerDiagnosticReadiness();
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
                case RECORDED -> emissions.add(() -> BootstrapFailureLog.escalated(escalation.failure(), escalationCause));
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

    private void logAdmissionRejection(BootstrapAdmissionRejectedException rejection) {
        if (Thread.holdsLock(lifecycle.monitor())) {
            return;
        }

        try {
            BootstrapFailureLog.rejected(rejection.failure());
        } catch (RuntimeException | Error loggingFailure) {
            PlatformThrowables.propagateIfFatal(loggingFailure);
            BootstrapCleanupFailures.suppressPreservingPrimary(rejection, loggingFailure);
        }
    }

    private void requireLifecycleMonitorNotHeld(Throwable failure) {
        if (Thread.holdsLock(lifecycle.monitor())) {
            IllegalMonitorStateException violation = new IllegalMonitorStateException(
                    "Bootstrap failure settlement must run outside the bootstrap lifecycle monitor"
            );
            violation.addSuppressed(failure);
            throw violation;
        }
    }

    private void registerDiagnosticReadiness() {
        diagnosticHandle.registerIfAbsent(
                () -> jmxRegistrar.registerDiagnosticReadiness(journal::current)
        );
    }

    private ExtensionRuntimeEvidence currentEvidence() {
        ExtensionRuntimeEvidence evidence = lifecycle.currentEvidence();
        return evidence == null ? unavailableEvidence() : evidence;
    }

    private ExtensionRuntimeEvidence unavailableEvidence() {
        return ExtensionRuntimeEvidence.unavailable(readiness.snapshot().extensionVersion());
    }

    private static Throwable closeActivationResources(BootstrapResources resources) {
        if (resources == null) {
            return null;
        }

        try {
            resources.close();
            return null;
        } catch (RuntimeException | Error failure) {
            return failure;
        }
    }

    private static Throwable closePreparedSession(AutoconfigureSession session) {
        if (session == null) {
            return null;
        }

        try {
            session.close();
            return null;
        } catch (RuntimeException | Error failure) {
            return failure;
        }
    }

    private static void rethrowIfPresent(Throwable failure) {
        if (failure != null) {
            BootstrapCleanupFailures.rethrow(failure);
        }
    }
}
