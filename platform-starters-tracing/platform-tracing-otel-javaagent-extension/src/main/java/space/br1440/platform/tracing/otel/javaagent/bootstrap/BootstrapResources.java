package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

final class BootstrapResources implements AutoCloseable {

    private final Deque<Registration> registrations = new ArrayDeque<>();
    private final ShutdownHookRegistry hookRegistry;
    private final Object lock = new Object();

    private Registration shutdownHook;
    private boolean closed;

    BootstrapResources(ShutdownHookRegistry hookRegistry) {
        this.hookRegistry = Objects.requireNonNull(hookRegistry, "hookRegistry");
    }

    void push(AutoCloseable resource, String label) {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Platform bootstrap resources are already closed");
            }

            registrations.push(Registration.adapt(resource, label));
        }
    }

    @SuppressWarnings("SameParameterValue")
    void installShutdownHook(String threadName) {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Platform bootstrap resources are already closed");
            }

            if (shutdownHook != null) {
                return;
            }

            shutdownHook = hookRegistry.register(threadName, this::closeFromShutdownHook);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;

            RuntimeException aggregate = null;
            if (shutdownHook != null) {
                try {
                    shutdownHook.close();
                } catch (RuntimeException failure) {
                    aggregate = new IllegalStateException("Platform bootstrap resource cleanup failed", failure);
                }
                shutdownHook = null;
            }

            while (!registrations.isEmpty()) {
                Registration registration = registrations.pop();
                try {
                    registration.close();
                } catch (RuntimeException failure) {
                    aggregate = newAggregate(aggregate, failure);
                }
            }

            if (aggregate != null) {
                throw aggregate;
            }
        }
    }

    private static RuntimeException newAggregate(RuntimeException aggregate, RuntimeException failure) {
        if (aggregate == null) {
            return new IllegalStateException("Platform bootstrap resource cleanup failed", failure);
        }

        aggregate.addSuppressed(failure);
        return aggregate;
    }

    private void closeFromShutdownHook() {
        try {
            close();
        } catch (RuntimeException failure) {
            System.err.println("[platform-tracing] shutdown-hook resource cleanup failed: " + failure);
        }
    }
}
