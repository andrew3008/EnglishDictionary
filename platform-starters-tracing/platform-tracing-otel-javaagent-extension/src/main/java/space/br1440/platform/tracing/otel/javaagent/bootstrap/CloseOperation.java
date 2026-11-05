package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.BootstrapFailure;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapResources;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;

final class CloseOperation {

    record CloseContext(BootstrapFailure failureBeforeClose,
                        BootstrapResources resources,
                        AutoconfigureSession session) {
    }

    enum Role {
        OWNER,
        FOLLOWER,
        REENTRANT
    }

    record CloseAcquisition(Role role, CloseOperation operation) {

        CloseAcquisition {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(operation, "operation");
        }

        static CloseAcquisition owner(CloseOperation operation) {
            return new CloseAcquisition(Role.OWNER, operation);
        }

        static CloseAcquisition follower(CloseOperation operation) {
            return new CloseAcquisition(Role.FOLLOWER, operation);
        }

        static CloseAcquisition reentrant(CloseOperation operation) {
            return new CloseAcquisition(Role.REENTRANT, operation);
        }

        CloseContext context() {
            return operation.context();
        }

        Throwable awaitOutcome() {
            return operation.awaitOutcome();
        }
    }

    private final Thread owner;
    private final CloseContext context;
    private final CountDownLatch completion = new CountDownLatch(1);

    private volatile Throwable outcome;

    CloseOperation(Thread owner, CloseContext context) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.context = Objects.requireNonNull(context, "context");
    }

    CloseContext context() {
        return context;
    }

    boolean ownedByCurrentThread() {
        return owner == Thread.currentThread();
    }

    boolean inProgress() {
        return completion.getCount() != 0L;
    }

    void complete(Throwable completedOutcome) {
        if (!ownedByCurrentThread()) {
            throw new IllegalStateException("Only the bootstrap close owner can complete the close");
        }

        if (!inProgress()) {
            throw new IllegalStateException("Bootstrap close is already complete");
        }

        outcome = completedOutcome;
        completion.countDown();
    }

    Throwable awaitOutcome() {
        boolean interrupted = false;
        while (true) {
            try {
                completion.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        return outcome;
    }
}
