package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.CONFIGURATION_INITIALIZATION_FAILED;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapCleanupFailures;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapResources;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.JvmShutdownHookRegistry;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.ShutdownHookRegistry;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSessionFactory;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.ConfigurationPropertyException;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.verification.ExtensionRuntimeVerifier;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxMutationRegistrationAssessor;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.SanitizerBootstrapException;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

final class BootstrapCoordinator implements AutoCloseable {

    private static final String PREPARE_PHASE = "prepare";

    private final BootstrapLifecycleState lifecycle;
    private final BootstrapReadinessJournal journal;
    private final DiagnosticReadinessHandle diagnosticHandle;
    private final PlatformTracingMBeanRegistrar jmxRegistrar;
    private final PlatformExtensionReadiness readiness;
    private final AgentInstanceId agentInstanceId;
    private final BootstrapTerminalTransitions terminalTransitions;
    private final AutoconfigureSessionFactory sessionFactory;
    private final BootstrapActivation activation;

    static BootstrapCoordinator createDefault() {
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        return new BootstrapCoordinator(
                JvmShutdownHookRegistry.INSTANCE,
                new PlatformScrubberFactory(),
                ExtensionRuntimeVerifier.forCurrentJvm(),
                AutoconfigureSessionFactory::currentProcessRemoteJmxAssessor,
                new PlatformTracingMBeanRegistrar(agentInstanceId)
        );
    }

    BootstrapCoordinator(ShutdownHookRegistry hookRegistry,
                         PlatformScrubberFactory scrubberFactory,
                         ExtensionRuntimeVerifier runtimeVerifier,
                         Function<ConfigProperties, RemoteJmxMutationRegistrationAssessor> remoteJmxAssessorFactory) {
        this(
                hookRegistry,
                scrubberFactory,
                runtimeVerifier,
                remoteJmxAssessorFactory,
                registrarForNewAgentInstance()
        );
    }

    BootstrapCoordinator(ShutdownHookRegistry hookRegistry,
                         PlatformScrubberFactory scrubberFactory,
                         ExtensionRuntimeVerifier runtimeVerifier,
                         Function<ConfigProperties, RemoteJmxMutationRegistrationAssessor> remoteJmxAssessorFactory,
                         PlatformTracingMBeanRegistrar jmxRegistrar) {
        this.lifecycle = new BootstrapLifecycleState();
        this.journal = new BootstrapReadinessJournal(lifecycle.monitor());
        this.diagnosticHandle = new DiagnosticReadinessHandle();
        this.jmxRegistrar = Objects.requireNonNull(jmxRegistrar, "jmxRegistrar");
        this.readiness = jmxRegistrar.extensionReadiness();
        this.agentInstanceId = jmxRegistrar.agentInstanceId();
        this.terminalTransitions = new BootstrapTerminalTransitions(lifecycle, journal, diagnosticHandle);
        this.sessionFactory = new AutoconfigureSessionFactory(scrubberFactory, remoteJmxAssessorFactory);
        this.activation = new BootstrapActivation(
                lifecycle,
                journal,
                terminalTransitions,
                diagnosticHandle,
                runtimeVerifier,
                hookRegistry
        );
    }

    AutoconfigureSession prepare(ConfigProperties config) {
        Objects.requireNonNull(config, "config");

        lifecycle.beginPrepare();

        try {
            AutoconfigureSession prepared = sessionFactory.create(
                    config,
                    jmxRegistrar,
                    agentInstanceId
            );
            lifecycle.completePrepare(prepared);
            return prepared;
        } catch (SanitizerBootstrapException e) {
            throw failPreparation(e.code(), e);
        } catch (ConfigurationPropertyException e) {
            throw failPreparation(
                    CONFIGURATION_INITIALIZATION_FAILED,
                    "configuration-validation",
                    e.propertyName(),
                    e.failure()
            );
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
        } catch (RuntimeException | Error e) {
            PlatformThrowables.propagateIfFatal(e);
            throw failCustomization(failureCode, failureCode, e);
        }
    }

    void activate() {
        activation.activate();
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
            terminalTransitions.completeCloseFailure(cleanupFailure);
            BootstrapCleanupFailures.rethrow(cleanupFailure);
        }

        lifecycle.markClosed();
    }

    private BootstrapException failCustomization(String code, String phase, Throwable cause) {
        BootstrapException classified = lifecycle.fail(code, phase, cause);
        terminalTransitions.publishSessionFailure(lifecycle.currentSession(), code);
        return classified;
    }

    private BootstrapException failPreparation(String code, Throwable cause) {
        return failPreparation(code, "configuration-initialization", null, cause);
    }

    private BootstrapException failPreparation(String code,
                                               String subphase,
                                               String propertyName,
                                               Throwable cause) {
        try {
            journal.publishFailed(readiness, agentInstanceId, code);
            registerDiagnosticReadiness();
        } catch (RuntimeException | Error diagnosticFailure) {
            if (diagnosticFailure != cause) {
                cause.addSuppressed(diagnosticFailure);
            }

            BootstrapException classified = lifecycle.fail(
                    code,
                    PREPARE_PHASE,
                    subphase,
                    propertyName,
                    cause
            );
            PlatformThrowables.propagateIfFatal(diagnosticFailure);
            return classified;
        }
        return lifecycle.fail(code, PREPARE_PHASE, subphase, propertyName, cause);
    }

    private void registerDiagnosticReadiness() {
        diagnosticHandle.registerIfAbsent(
                () -> jmxRegistrar.registerDiagnosticReadiness(journal::current)
        );
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

    private static PlatformTracingMBeanRegistrar registrarForNewAgentInstance() {
        return new PlatformTracingMBeanRegistrar(AgentInstanceId.random());
    }
}
