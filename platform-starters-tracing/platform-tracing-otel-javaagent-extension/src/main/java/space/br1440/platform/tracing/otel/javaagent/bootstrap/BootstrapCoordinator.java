package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.BootstrapFailureCodes.CONFIGURATION_INITIALIZATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.BootstrapFailureCodes.DIAGNOSTIC_MBEAN_REGISTRATION_FAILED;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.BootstrapAdmissionRejectedException;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.BootstrapException;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.BootstrapFailure;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.BootstrapFailureCodes;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.BootstrapFailureLog;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.CustomizationStep;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.FailureContext;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.FailureSite;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapCleanupFailures;
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
    private final FailureHandler failures;
    private final AutoconfigureSessionFactory sessionFactory;
    private final ExtensionRuntimeVerifier runtimeVerifier;
    private final BootstrapActivation activation;
    private final PreparationGate preparationGate;
    private final BootstrapCloser closer;

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

        BootstrapTerminalTransitions terminalTransitions = new BootstrapTerminalTransitions(lifecycle, journal);
        this.failures = new FailureHandler(lifecycle, terminalTransitions, this::registerDiagnosticReadiness);
        this.sessionFactory = new AutoconfigureSessionFactory(scrubberFactory, remoteJmxAssessorFactory);
        this.runtimeVerifier = Objects.requireNonNull(runtimeVerifier, "runtimeVerifier");
        this.activation = new BootstrapActivation(
                lifecycle,
                journal,
                terminalTransitions
        );
        this.preparationGate = new PreparationGate(lifecycle);
        this.closer = new BootstrapCloser(
                lifecycle,
                terminalTransitions,
                this.diagnosticHandle,
                hookRegistry,
                preparationGate
        );
    }

    AutoconfigureSession prepare(ConfigProperties config) {
        Objects.requireNonNull(config, "config");

        try {
            preparationGate.enter();
        } catch (BootstrapAdmissionRejectedException rejection) {
            logAdmissionRejection(rejection);
            throw rejection;
        }

        try {
            return prepareAsOwner(config);
        } finally {
            preparationGate.leave();
        }
    }

    private AutoconfigureSession prepareAsOwner(ConfigProperties config) {
        try {
            journal.publishInitializing();
        } catch (RuntimeException | Error publicationFailure) {
            throw new BootstrapException(failures.handle(
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
            throw new BootstrapException(failures.handle(
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
            throw new BootstrapException(failures.handle(
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
            case VerificationException verification -> failures.handle(
                    FailureSite.prepareConfiguration(),
                    verification.code().name(),
                    verification,
                    null,
                    evidence
            );

            case SanitizerBootstrapException sanitizer when sanitizer.propertyName() != null -> failures.handle(
                    FailureSite.prepareValidation(sanitizer.propertyName()),
                    sanitizer.code(),
                    sanitizer,
                    null,
                    evidence
            );

            case ConfigurationPropertyException property -> failures.handle(
                    FailureSite.prepareValidation(property.propertyName()),
                    CONFIGURATION_INITIALIZATION_FAILED,
                    property.failure(),
                    null,
                    evidence
            );

            default -> failures.handle(
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
            throw new BootstrapException(failures.handle(
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

        Optional<BootstrapFailure> missingPreparation = failures.handleMissingPreparation(unavailableEvidence());
        if (missingPreparation.isPresent()) {
            throw new BootstrapException(missingPreparation.get());
        }

        Optional<FailureContext> activationFailure;
        try {
            activationFailure = activation.activate(tracerProvider, closer::installShutdownHook);
        } catch (BootstrapAdmissionRejectedException rejection) {
            logAdmissionRejection(rejection);
            throw rejection;
        }

        if (activationFailure.isPresent()) {
            throw new BootstrapException(failures.complete(activationFailure.get()));
        }
    }

    void onStartupFailure(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        failures.handleStartup(cause, currentEvidence());
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
        closer.close();
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

    private void registerDiagnosticReadiness() {
        diagnosticHandle.registerIfAbsent(
                () -> jmxRegistrar.registerDiagnosticReadiness(journal::current)
        );
    }

    private ExtensionRuntimeEvidence currentEvidence() {
        ExtensionRuntimeEvidence evidence = lifecycle.currentEvidence();
        return (evidence == null) ? unavailableEvidence() : evidence;
    }

    private ExtensionRuntimeEvidence unavailableEvidence() {
        return ExtensionRuntimeEvidence.unavailable(readiness.snapshot().extensionVersion());
    }
}
