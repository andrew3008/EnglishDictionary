package space.br1440.platform.tracing.otel.javaagent.bootstrap.resources;

import java.util.ArrayDeque;
import java.util.Deque;

public final class BootstrapResources implements AutoCloseable {

    private final Deque<Registration> registrations = new ArrayDeque<>();
    private final Object lock = new Object();

    private boolean closed;
    private Throwable closeFailure;

    public BootstrapResources() {
    }

    public void push(AutoCloseable resource, String label) {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Platform bootstrap resources are already closed");
            }

            registrations.push(Registration.adapt(resource, label));
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                if (closeFailure != null) {
                    BootstrapCleanupFailures.rethrow(closeFailure);
                }

                return;
            }

            closed = true;

            Throwable aggregate = null;
            while (!registrations.isEmpty()) {
                Registration registration = registrations.pop();
                try {
                    registration.close();
                } catch (RuntimeException | Error failure) {
                    aggregate = BootstrapCleanupFailures.aggregate(aggregate, failure);
                }
            }

            if (aggregate != null) {
                closeFailure = aggregate;
                BootstrapCleanupFailures.rethrow(aggregate);
            }
        }
    }
}
