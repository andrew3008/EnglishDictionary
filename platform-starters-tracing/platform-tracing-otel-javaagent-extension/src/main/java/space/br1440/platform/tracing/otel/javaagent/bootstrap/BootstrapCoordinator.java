package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxMutationRegistrationGate;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.SanitizerBootstrapException;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

final class BootstrapCoordinator implements AutoCloseable {

    private final AutoconfigureSessionFactory sessionFactory;
    private final BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
    private final BootstrapReadinessJournal journal = new BootstrapReadinessJournal(lifecycle.monitor());
    private final DiagnosticReadinessHandle diagnosticHandle = new DiagnosticReadinessHandle();
    private final BootstrapTerminalTransitions terminalTransitions = new BootstrapTerminalTransitions(lifecycle, journal, diagnosticHandle);
    private final BootstrapActivation activation;

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

    static BootstrapCoordinator create(PlatformScrubberFactory scrubberFactory,
                                       RuntimeEvidenceAttestor runtimeEvidenceAttestor,
                                       Function<ConfigProperties, RemoteJmxMutationRegistrationGate> remoteJmxExposurePolicyFactory) {
        return new BootstrapCoordinator(
                JvmShutdownHookRegistry.INSTANCE,
                scrubberFactory,
                runtimeEvidenceAttestor,
                remoteJmxExposurePolicyFactory
        );
    }

    BootstrapCoordinator(ShutdownHookRegistry hookRegistry,
                         PlatformScrubberFactory scrubberFactory,
                         RuntimeEvidenceAttestor runtimeEvidenceAttestor) {
        this(
                hookRegistry,
                scrubberFactory,
                runtimeEvidenceAttestor,
                AutoconfigureSessionFactory::currentProcessRemoteJmxPolicy);
    }

    BootstrapCoordinator(
            ShutdownHookRegistry hookRegistry,
            PlatformScrubberFactory scrubberFactory,
            RuntimeEvidenceAttestor runtimeEvidenceAttestor,
            Function<ConfigProperties, RemoteJmxMutationRegistrationGate> remoteJmxExposurePolicyFactory) {
        this.sessionFactory = new AutoconfigureSessionFactory(scrubberFactory, remoteJmxExposurePolicyFactory);
        this.activation = new BootstrapActivation(
                lifecycle,
                journal,
                terminalTransitions,
                diagnosticHandle,
                Objects.requireNonNull(runtimeEvidenceAttestor, "runtimeEvidenceAttestor"),
                Objects.requireNonNull(hookRegistry, "hookRegistry")
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
            throw lifecycle.fail(e.code(), "prepare", e);
        } catch (RuntimeException | Error e) {
            PlatformThrowables.propagateIfFatal(e);
            throw lifecycle.fail("CONFIGURATION_INITIALIZATION_FAILED", "prepare", e);
        }
    }

    AutoconfigureSession session() {
        return lifecycle.requireSession();
    }

    <T> T execute(String phaseFailureCode, Supplier<T> action) {
        lifecycle.requireOperable(phaseFailureCode);

        try {
            return action.get();
        } catch (SanitizerBootstrapException e) {
            BootstrapException classified = lifecycle.fail(e.code(), phaseFailureCode, e);
            publishSessionFailure(e.code(), e);
            throw classified;
        } catch (RuntimeException | Error e) {
            PlatformThrowables.propagateIfFatal(e);
            throw lifecycle.fail(phaseFailureCode, phaseFailureCode, e);
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

        Throwable cleanupFailure = null;
        if (transition.resources() != null) {
            try {
                transition.resources().close();
            } catch (RuntimeException | Error failure) {
                cleanupFailure = failure;
            }
        }

        try {
            closeDiagnosticHandle();
        } catch (RuntimeException | Error failure) {
            cleanupFailure = BootstrapCleanupFailures.aggregate(cleanupFailure, failure);
        }

        if (cleanupFailure != null) {
            terminalTransitions.completeCloseFailure(cleanupFailure);
            BootstrapCleanupFailures.rethrow(cleanupFailure);
        }

        lifecycle.markClosed();
    }

    void closeDiagnosticHandle() {
        diagnosticHandle.close();
    }

    private void publishSessionFailure(String failureCode, Throwable cause) {
        terminalTransitions.publishSessionFailure(lifecycle.currentSession(), failureCode, cause);
    }
}
