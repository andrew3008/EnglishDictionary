package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.CONFIGURATION_INITIALIZATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.DIAGNOSTIC_MBEAN_REGISTRATION_FAILED;

import java.util.Objects;
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
import space.br1440.platform.tracing.otel.javaagent.bootstrap.verification.VerificationFailureCode;
import space.br1440.platform.tracing.otel.javaagent.configuration.ConfigurationPropertyException;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
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

        acquirePreparationOwnership();
        try {
            return prepareAsOwner(config);
        } finally {
            releasePreparationOwnership();
        }
    }

    private AutoconfigureSession prepareAsOwner(ConfigProperties config) {
        try {
            journal.publishInitializing();
        } catch (RuntimeException | Error publicationFailure) {
            String failureCode = publicationFailure instanceof LinkageError
                    ? VerificationFailureCode.EXTENSION_LINKAGE_ERROR.name()
                    : CONFIGURATION_INITIALIZATION_FAILED;

            BootstrapException classified;
            try {
                classified = failPreparation(
                        failureCode,
                        "readiness-publication",
                        null,
                        publicationFailure,
                        journal.current().evidence()
                );
            } catch (RuntimeException | Error handlingFailure) {
                BootstrapCleanupFailures.suppressPreservingPrimary(publicationFailure, handlingFailure);
                PlatformThrowables.propagateIfFatal(publicationFailure);
                throw handlingFailure;
            }

            PlatformThrowables.propagateIfFatal(publicationFailure);
            throw classified;
        }

        AutoconfigureSession prepared = createPreparedSession(config);
        try {
            try {
                registerDiagnosticReadiness();
            } catch (RuntimeException | Error registrationFailure) {
                BootstrapException classified = terminalTransitions.terminalizePreparationFailure(
                        DIAGNOSTIC_MBEAN_REGISTRATION_FAILED,
                        "diagnostic-registration",
                        null,
                        registrationFailure,
                        prepared.evidence()
                );
                registerDiagnosticAfterFailure(classified);
                PlatformThrowables.propagateIfFatal(registrationFailure);
                throw classified;
            }

            lifecycle.completePrepare(prepared);
            return prepared;
        } catch (RuntimeException | Error failure) {
            Throwable cleanupFailure = closePreparedSession(prepared);
            if (cleanupFailure != null) {
                Throwable settledFailure = terminalTransitions.completeCleanupFailure(
                        "prepare",
                        failure,
                        cleanupFailure,
                        prepared.evidence()
                );

                if (settledFailure instanceof Error error) {
                    throw error;
                }

                throw (RuntimeException) settledFailure;
            }

            throw failure;
        }
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
        } catch (VerificationException e) {
            throw failPreparation(e.code().name(), e);
        } catch (LinkageError e) {
            //noinspection ThrowableNotThrown — пробрасывается тот же экземпляр LinkageError
            failPreparation(VerificationFailureCode.EXTENSION_LINKAGE_ERROR.name(), e);
            throw e;
        } catch (SanitizerBootstrapException e) {
            if (e.propertyName() == null) {
                throw failPreparation(e.code(), e);
            }
            throw failPreparation(
                    e.code(),
                    "configuration-validation",
                    e.propertyName(),
                    e
            );
        } catch (ConfigurationPropertyException e) {
            throw failPreparation(
                    CONFIGURATION_INITIALIZATION_FAILED,
                    "configuration-validation",
                    e.propertyName(),
                    e.failure()
            );
        } catch (BootstrapException classified) {
            throw classified;
        } catch (RuntimeException | Error e) {
            PlatformThrowables.propagateIfFatal(e);
            throw failPreparation(CONFIGURATION_INITIALIZATION_FAILED, e);
        }
    }

    AutoconfigureSession session() {
        return lifecycle.requireSession();
    }

    <T> T execute(String failureCode, Supplier<T> action) {
        Objects.requireNonNull(failureCode, "failureCode");
        lifecycle.requireOperable(failureCode);

        try {
            return action.get();
        } catch (SanitizerBootstrapException e) {
            throw failCustomization(e.code(), failureCode, e);
        } catch (LinkageError e) {
            //noinspection ThrowableNotThrown — пробрасывается тот же экземпляр LinkageError
            failCustomization(VerificationFailureCode.EXTENSION_LINKAGE_ERROR.name(), failureCode, e);
            throw e;
        } catch (RuntimeException | Error e) {
            throw failCustomization(failureCode, failureCode, e);
        }
    }

    void activate(SdkTracerProvider tracerProvider) {
        Objects.requireNonNull(tracerProvider, "tracerProvider");
        terminalTransitions.failMissingPreparation(unavailableEvidence(), this::registerDiagnosticReadiness);
        activation.activate(tracerProvider, this::installLifecycleShutdownHook);
    }

    ExtensionReadinessWireSnapshot currentWire() {
        return journal.current();
    }

    BootstrapState state() {
        return lifecycle.state();
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

    private BootstrapException failCustomization(String code, String phase, Throwable cause) {
        ExtensionRuntimeEvidence evidence = currentEvidence();
        BootstrapException classified = null;
        Throwable outcome = cause;
        try {
            try {
                classified = terminalTransitions.terminalizeCustomizationFailure(code, phase, cause, evidence);
                registerDiagnosticAfterFailure(classified);
                return classified;
            } catch (RuntimeException | Error handlingFailure) {
                BootstrapCleanupFailures.suppressPreservingPrimary(cause, handlingFailure);
                outcome = BootstrapCleanupFailures.aggregate(cause, handlingFailure);
                throw handlingFailure;
            } finally {
                Throwable cleanupFailure = closePreparedSession(lifecycle.detachFailedSession());
                if (cleanupFailure != null) {
                    BootstrapCleanupFailures.suppressPreservingPrimary(cause, cleanupFailure);
                    outcome = BootstrapCleanupFailures.aggregate(outcome, cleanupFailure);

                    Throwable settledFailure = terminalTransitions.completeCleanupFailure(
                            "customization",
                            classified == null ? outcome : classified,
                            cleanupFailure,
                            evidence
                    );
                    rethrowIfPresent(settledFailure);
                }
            }
        } finally {
            PlatformThrowables.propagateIfFatal(outcome);
        }
    }

    private BootstrapException failPreparation(String code, Throwable cause) {
        return failPreparation(code, "configuration-initialization", null, cause);
    }

    private BootstrapException failPreparation(String code,
                                               String subphase,
                                               String propertyName,
                                               Throwable cause) {
        return failPreparation(code, subphase, propertyName, cause, unavailableEvidence());
    }

    private BootstrapException failPreparation(String code,
                                               String subphase,
                                               String propertyName,
                                               Throwable cause,
                                               ExtensionRuntimeEvidence evidence) {
        BootstrapException classified = terminalTransitions.terminalizePreparationFailure(
                code,
                subphase,
                propertyName,
                cause,
                evidence
        );
        registerDiagnosticAfterFailure(classified);
        return classified;
    }

    private void registerDiagnosticReadiness() {
        diagnosticHandle.registerIfAbsent(
                () -> jmxRegistrar.registerDiagnosticReadiness(journal::current)
        );
    }

    private void registerDiagnosticAfterFailure(BootstrapException classified) {
        try {
            registerDiagnosticReadiness();
        } catch (RuntimeException | Error diagnosticFailure) {
            BootstrapCleanupFailures.suppressPreservingPrimary(classified.failure().cause(), diagnosticFailure);
            PlatformThrowables.propagateIfFatal(diagnosticFailure);
        }
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
