package space.br1440.platform.tracing.otel.javaagent.bootstrap.resources;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Consumer;

import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.BootstrapLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

public final class BootstrapResources implements AutoCloseable {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(BootstrapResources.class);

    private final Deque<Registration> registrations = new ArrayDeque<>();
    private final ShutdownHookRegistry hookRegistry;
    private final Object lock = new Object();

    private Registration shutdownHook;
    private boolean closed;
    private Throwable closeFailure;

    public BootstrapResources(ShutdownHookRegistry hookRegistry) {
        this.hookRegistry = Objects.requireNonNull(hookRegistry, "hookRegistry");
    }

    public void push(AutoCloseable resource, String label) {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Platform bootstrap resources are already closed");
            }

            registrations.push(Registration.adapt(resource, label));
        }
    }

    @SuppressWarnings("SameParameterValue")
    public void installShutdownHook(String threadName, Consumer<Throwable> completion) {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Platform bootstrap resources are already closed");
            }

            if (shutdownHook != null) {
                return;
            }

            Consumer<Throwable> shutdownCompletion = Objects.requireNonNull(completion, "completion");
            shutdownHook = hookRegistry.register(
                    threadName,
                    () -> closeFromShutdownHook(shutdownCompletion)
            );
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                if (closeFailure != null) {
                    rethrow(closeFailure);
                }

                return;
            }

            closed = true;

            Throwable aggregate = null;
            if (shutdownHook != null) {
                try {
                    shutdownHook.close();
                } catch (RuntimeException | Error failure) {
                    aggregate = aggregate(aggregate, failure, "shutdown-hook");
                }

                shutdownHook = null;
            }

            while (!registrations.isEmpty()) {
                Registration registration = registrations.pop();
                try {
                    registration.close();
                } catch (RuntimeException | Error failure) {
                    aggregate = aggregate(aggregate, failure, null);
                }
            }

            if (aggregate != null) {
                closeFailure = aggregate;
                rethrow(aggregate);
            }
        }
    }

    private static Throwable aggregate(Throwable aggregate, Throwable failure, String resourceLabel) {
        if (aggregate == null && failure instanceof Error) {
            return failure;
        }

        if (aggregate == null) {
            return labeledFailure(failure, resourceLabel);
        }

        if (failure instanceof Error && !(aggregate instanceof Error)) {
            failure.addSuppressed(aggregate);
            return failure;
        }

        aggregate.addSuppressed(labeledFailure(failure, resourceLabel));
        return aggregate;
    }

    private static Throwable labeledFailure(Throwable failure, String resourceLabel) {
        if (failure instanceof BootstrapResourceCleanupException || resourceLabel == null) {
            return failure;
        }

        return new BootstrapResourceCleanupException(resourceLabel, failure);
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }

        throw (RuntimeException) failure;
    }

    private void closeFromShutdownHook(Consumer<Throwable> completion) {
        Throwable cleanupFailure = null;
        try {
            close();
        } catch (RuntimeException | Error failure) {
            cleanupFailure = failure;
        } finally {
            try {
                completion.accept(cleanupFailure);
            } catch (RuntimeException | Error failure) {
                Throwable completionFailure = BootstrapCleanupFailures.withResourceLabel(
                        "shutdown-completion",
                        failure
                );

                Throwable terminalFailure = BootstrapCleanupFailures.aggregate(
                        cleanupFailure,
                        completionFailure
                );

                log.log(BootstrapLogEvent.BOOTSTRAP_SHUTDOWN_COMPLETION_FAILED, fields -> fields
                        .putString(AgentLogField.PHASE, "shutdown")
                        .putString(AgentLogField.RESOURCE_LABEL, "shutdown-completion")
                        .cause(terminalFailure));
            }
        }
    }
}
