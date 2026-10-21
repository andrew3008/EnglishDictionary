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

    private final BootstrapLifecycleState lifecycle;
    private final BootstrapReadinessJournal journal;
    private final DiagnosticReadinessHandle diagnosticHandle;
    private final PlatformTracingMBeanRegistrar jmxRegistrar;
    private final PlatformExtensionReadiness readiness;
    private final AgentInstanceId agentInstanceId;
    private final BootstrapTerminalTransitions terminalTransitions;
    private final AutoconfigureSessionFactory sessionFactory;
    private final ExtensionRuntimeVerifier runtimeVerifier;
    private final BootstrapActivation activation;

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
        this.jmxRegistrar = Objects.requireNonNull(jmxRegistrar, "jmxRegistrar");
        this.readiness = jmxRegistrar.extensionReadiness();
        this.agentInstanceId = jmxRegistrar.agentInstanceId();
        this.journal = new BootstrapReadinessJournal(lifecycle.monitor(), readiness, agentInstanceId);
        this.terminalTransitions = new BootstrapTerminalTransitions(lifecycle, journal, diagnosticHandle);
        this.sessionFactory = new AutoconfigureSessionFactory(scrubberFactory, remoteJmxAssessorFactory);
        this.runtimeVerifier = Objects.requireNonNull(runtimeVerifier, "runtimeVerifier");
        this.activation = new BootstrapActivation(
                lifecycle,
                journal,
                terminalTransitions,
                hookRegistry
        );
    }

    AutoconfigureSession prepare(ConfigProperties config) {
        Objects.requireNonNull(config, "config");

        lifecycle.beginPrepare();
        journal.publishInitializing();

        AutoconfigureSession prepared = createPreparedSession(config);

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
            throw failPreparation(e.code(), e);
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
            PlatformThrowables.propagateIfFatal(e);
            throw failCustomization(failureCode, failureCode, e);
        }
    }

    void activate(SdkTracerProvider tracerProvider) {
        Objects.requireNonNull(tracerProvider, "tracerProvider");
        terminalTransitions.failMissingPreparation(unavailableEvidence(), this::registerDiagnosticReadiness);
        activation.activate(tracerProvider);
    }

    ExtensionReadinessWireSnapshot currentWire() {
        return journal.current();
    }

    BootstrapState state() {
        return lifecycle.state();
    }

    @Override
    public void close() {
        BootstrapLifecycleState.CloseTransition transition = lifecycle.beginClose();
        if (!transition.started()) {
            return;
        }

        Throwable cleanupFailure = closeActivationResources(transition.resources());
        try {
            diagnosticHandle.close();
        } catch (RuntimeException | Error failure) {
            cleanupFailure = BootstrapCleanupFailures.aggregate(
                    cleanupFailure,
                    BootstrapCleanupFailures.withResourceLabel("diagnostic-readiness", failure)
            );
        }

        if (cleanupFailure != null) {
            terminalTransitions.completeCloseFailure(cleanupFailure, "", currentEvidence());
            BootstrapCleanupFailures.rethrow(cleanupFailure);
        }

        lifecycle.markClosed();
    }

    private BootstrapException failCustomization(String code, String phase, Throwable cause) {
        BootstrapException classified = terminalTransitions.terminalizeCustomizationFailure(
                code,
                phase,
                cause,
                currentEvidence()
        );
        registerDiagnosticAfterFailure(classified);
        return classified;
    }

    private BootstrapException failPreparation(String code, Throwable cause) {
        return failPreparation(code, "configuration-initialization", null, cause);
    }

    private BootstrapException failPreparation(String code,
                                               String subphase,
                                               String propertyName,
                                               Throwable cause) {
        BootstrapException classified = terminalTransitions.terminalizePreparationFailure(
                code,
                subphase,
                propertyName,
                cause,
                unavailableEvidence()
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
        AutoconfigureSession currentSession = lifecycle.currentSession();
        return currentSession == null ? unavailableEvidence() : currentSession.evidence();
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
}
