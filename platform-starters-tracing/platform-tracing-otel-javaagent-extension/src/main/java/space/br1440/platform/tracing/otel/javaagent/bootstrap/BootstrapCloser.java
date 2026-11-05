package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapCleanupFailures;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapResources;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.ShutdownHookRegistry;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;

final class BootstrapCloser {

    private static final String SHUTDOWN_HOOK_THREAD_NAME = "platform-tracing-bootstrap-shutdown";

    private final BootstrapLifecycleState lifecycle;
    private final BootstrapTerminalTransitions terminalTransitions;
    private final DiagnosticReadinessHandle diagnosticHandle;
    private final ShutdownHookRegistry hookRegistry;
    private final PreparationGate preparationGate;

    private boolean shutdownHookInstalled;

    BootstrapCloser(BootstrapLifecycleState lifecycle,
                    BootstrapTerminalTransitions terminalTransitions,
                    DiagnosticReadinessHandle diagnosticHandle,
                    ShutdownHookRegistry hookRegistry,
                    PreparationGate preparationGate) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.terminalTransitions = Objects.requireNonNull(terminalTransitions, "terminalTransitions");
        this.diagnosticHandle = Objects.requireNonNull(diagnosticHandle, "diagnosticHandle");
        this.hookRegistry = Objects.requireNonNull(hookRegistry, "hookRegistry");
        this.preparationGate = Objects.requireNonNull(preparationGate, "preparationGate");
    }

    void close() {
        CloseOperation.CloseAcquisition acquisition = preparationGate.acquireCloseWhenIdle();
        switch (acquisition.role()) {
            case REENTRANT -> {
            }

            case FOLLOWER -> {
                rethrowIfPresent(acquisition.awaitOutcome());
            }

            case OWNER -> {
                Throwable outcome = null;
                try {
                    outcome = closeAsOwner(acquisition.context());
                } catch (RuntimeException | Error unexpectedFailure) {
                    outcome = unexpectedFailure;
                } finally {
                    lifecycle.completeClose(acquisition.operation(), outcome);
                }

                rethrowIfPresent(outcome);
            }
        }
    }

    void installShutdownHook() {
        if (!Thread.holdsLock(lifecycle.monitor())) {
            throw new IllegalMonitorStateException(
                    "Lifecycle shutdown hook installation requires the bootstrap lifecycle monitor"
            );
        }

        if (shutdownHookInstalled) {
            return;
        }

        hookRegistry.register(SHUTDOWN_HOOK_THREAD_NAME, this::closeFromShutdownHook);
        shutdownHookInstalled = true;
    }

    private Throwable closeAsOwner(CloseOperation.CloseContext context) {
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
            return terminalTransitions.completeCloseFailure(
                    context,
                    cleanupFailure,
                    priorPublicationFailure,
                    false
            );
        }

        BootstrapResources resources = context.resources();
        Throwable runtimeCleanupFailure;
        if (resources != null) {
            runtimeCleanupFailure = BootstrapCleanupFailures.capture(resources::close);
        } else {
            //noinspection resource -- session закрывается через capture в этом методе
            AutoconfigureSession session = context.session();
            runtimeCleanupFailure = session == null ? null : BootstrapCleanupFailures.capture(session::close);
        }

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

    private void closeFromShutdownHook() {
        try {
            close();
        } catch (RuntimeException | Error ignored) {
        }
    }

    private static void rethrowIfPresent(Throwable failure) {
        if (failure != null) {
            BootstrapCleanupFailures.rethrow(failure);
        }
    }
}
