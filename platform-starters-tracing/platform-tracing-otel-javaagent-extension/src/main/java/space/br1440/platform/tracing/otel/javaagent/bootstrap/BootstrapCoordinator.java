package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.CONFIGURATION_INITIALIZATION_FAILED;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxMutationRegistrationAssessor;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.SanitizerBootstrapException;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

final class BootstrapCoordinator implements AutoCloseable {

    private static final String PREPARE_PHASE = "prepare";

    private final BootstrapLifecycleState lifecycle;
    private final BootstrapReadinessJournal journal;
    private final DiagnosticReadinessHandle diagnosticHandle;
    private final BootstrapTerminalTransitions terminalTransitions;
    private final AutoconfigureSessionFactory sessionFactory;
    private final BootstrapActivation activation;

    static BootstrapCoordinator create() {
        return new BootstrapCoordinator(
                JvmShutdownHookRegistry.INSTANCE,
                new PlatformScrubberFactory(),
                ControlledAgentRuntimeAttestor.production(),
                AutoconfigureSessionFactory::currentProcessRemoteJmxAssessor
        );
    }

    BootstrapCoordinator(ShutdownHookRegistry hookRegistry,
                         PlatformScrubberFactory scrubberFactory,
                         RuntimeEvidenceAttestor runtimeEvidenceAttestor,
                         Function<ConfigProperties, RemoteJmxMutationRegistrationAssessor> remoteJmxAssessorFactory) {
        this.lifecycle = new BootstrapLifecycleState();
        this.journal = new BootstrapReadinessJournal(lifecycle.monitor());
        this.diagnosticHandle = new DiagnosticReadinessHandle();
        this.terminalTransitions = new BootstrapTerminalTransitions(lifecycle, journal, diagnosticHandle);
        this.sessionFactory = new AutoconfigureSessionFactory(scrubberFactory, remoteJmxAssessorFactory);
        this.activation = new BootstrapActivation(
                lifecycle,
                journal,
                terminalTransitions,
                diagnosticHandle,
                runtimeEvidenceAttestor,
                hookRegistry
        );
    }

    AutoconfigureSession prepare(ConfigProperties config) {
        Objects.requireNonNull(config, "config");

        ConfigurationFingerprint fingerprint = ConfigurationFingerprint.of(config);
        lifecycle.beginPrepare(fingerprint);

        try {
            AutoconfigureSession prepared = sessionFactory.create(config, fingerprint);
            lifecycle.completePrepare(prepared);
            return prepared;
        } catch (SanitizerBootstrapException e) {
            throw lifecycle.fail(e.code(), PREPARE_PHASE, e);
        } catch (RuntimeException | Error e) {
            PlatformThrowables.propagateIfFatal(e);
            throw lifecycle.fail(CONFIGURATION_INITIALIZATION_FAILED, PREPARE_PHASE, e);
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
            cleanupFailure = BootstrapCleanupFailures.aggregate(cleanupFailure, failure);
        }

        if (cleanupFailure != null) {
            terminalTransitions.completeCloseFailure(cleanupFailure);
            BootstrapCleanupFailures.rethrow(cleanupFailure);
        }

        lifecycle.markClosed();
    }

    private BootstrapException failCustomization(String code, String phase, Throwable cause) {
        BootstrapException classified = lifecycle.fail(code, phase, cause);
        terminalTransitions.publishSessionFailure(lifecycle.currentSession(), code, cause);
        return classified;
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
