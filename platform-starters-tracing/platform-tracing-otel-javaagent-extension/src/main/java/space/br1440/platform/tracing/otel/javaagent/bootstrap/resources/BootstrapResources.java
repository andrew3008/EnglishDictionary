package space.br1440.platform.tracing.otel.javaagent.bootstrap.resources;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Consumer;

public final class BootstrapResources implements AutoCloseable {

    private final Deque<Registration> registrations = new ArrayDeque<>();
    private final ShutdownHookRegistry hookRegistry;
    private final Object lock = new Object();

    private Registration shutdownHook;
    private boolean closed;

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
                return;
            }
            closed = true;

            Throwable aggregate = null;
            if (shutdownHook != null) {
                try {
                    shutdownHook.close();
                } catch (RuntimeException | Error failure) {
                    aggregate = aggregate(aggregate, failure);
                }
                shutdownHook = null;
            }

            while (!registrations.isEmpty()) {
                Registration registration = registrations.pop();
                try {
                    registration.close();
                } catch (RuntimeException | Error failure) {
                    aggregate = aggregate(aggregate, failure);
                }
            }

            if (aggregate != null) {
                rethrow(aggregate);
            }
        }
    }

    private static Throwable aggregate(Throwable aggregate, Throwable failure) {
        if (aggregate == null && failure instanceof Error) {
            return failure;
        }
        if (aggregate == null) {
            return new IllegalStateException("Platform bootstrap resource cleanup failed", failure);
        }
        if (failure instanceof Error && !(aggregate instanceof Error)) {
            failure.addSuppressed(aggregate);
            return failure;
        }

        aggregate.addSuppressed(failure);
        return aggregate;
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
            System.err.println("[platform-tracing] shutdown-hook resource cleanup failed: " + failure);
        } finally {
            try {
                completion.accept(cleanupFailure);
            } catch (RuntimeException | Error failure) {
                System.err.println("[platform-tracing] shutdown-hook completion failed: " + failure);
            }
        }
    }
}
